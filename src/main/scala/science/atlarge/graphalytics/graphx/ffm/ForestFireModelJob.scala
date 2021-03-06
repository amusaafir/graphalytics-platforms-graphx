/*
 * Copyright 2015 Delft University of Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package science.atlarge.graphalytics.graphx.ffm

import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import science.atlarge.graphalytics.domain.algorithms.ForestFireModelParameters
import science.atlarge.graphalytics.graphx.{GraphXJob, GraphXJobOutput}
import science.atlarge.graphalytics.domain.algorithms.ForestFireModelParameters
import science.atlarge.graphalytics.graphx.{GraphXJob, GraphXJobOutput}

import scala.util.Random

/**
 * The implementation of the graph evolution (forest fire model) algorithm on GraphX.
 *
 * @param graphVertexPath the path of the input graph's vertex data
 * @param graphEdgePath the path of the input graph's edge data
 * @param isDirected the directedness of the graph data
 * @param outputPath the output path of the computation
 * @author Tim Hegeman
 */
class ForestFireModelJob(graphVertexPath : String, graphEdgePath : String, isDirected : Boolean,
		outputPath : String, parameters : Object)
		extends GraphXJob[Boolean, Int](graphVertexPath, graphEdgePath, isDirected, outputPath) {

	val evoParam : ForestFireModelParameters = parameters match {
		case p : ForestFireModelParameters => p
		case _ => null
	}

	/**
	 * Draws a value for a geometric distribution with parameter p, from possible values [0, 1, 2, ...)
	 *
	 * @param p parameter of geometric distribution
	 * @return a random value
	 */
	def geometricRandom(p: Double) =
		if (p == 1.0) {
			(seed: Long) => 0
		} else {
			val logP = Math.log(1 - p)
			(seed: Long) => {
				val rand = new Random(seed)
				(Math.log(rand.nextDouble()) / logP).toInt
			}
		}
	val outLinkRandom: (Long) => Int = geometricRandom(evoParam.getPRatio)
	val inLinkRandom: (Long) => Int = geometricRandom(evoParam.getRRatio)

	/**
	 * @param number the number of links to select
	 * @param links the set of links to select from
	 * @return a random selection
	 */
	def selectRandomLinks(number: Int, links: Set[VertexId], seed: Long) =
		new Random(seed).shuffle(links.toList).take(number)

	/**
	 * Perform the graph computation using job-specific logic.
	 *
	 * @param graph the parsed graph with default vertex and edge values
	 * @return the resulting graph after the computation
	 */
	override def compute(graph: Graph[Boolean, Int]): Graph[Boolean, Int] = {
		val sparkContext = graph.vertices.context
		// Create random vertices
		val newVerts = sparkContext.parallelize(
			(evoParam.getMaxId + 1 to evoParam.getMaxId + evoParam.getNumNewVertices).
					map(v => (graph.pickRandomVertex(), Set(v)))
		)
		var edgeList = graph.vertices.aggregateUsingIndex[Set[VertexId]](newVerts, _ ++ _).cache()
		var burningVerts = edgeList
		// Merge the source data into the graph
		var g = graph.outerJoinVertices(edgeList) {
			(_, _, sources) => sources.getOrElse(Set.empty[VertexId])
		}.cache()

		// Perform a number of iterations of the forest fire simulation
		var i = 0
		while (i < evoParam.getMaxIterations && burningVerts.count() > 0) {
			// Select outgoing links and burn them
			val newOutLinks = {
				// Merge information on burning vertices into the graph
				val burningVertGraph = g.outerJoinVertices(burningVerts) {
					(_, data, burningOpt) => (data, burningOpt.getOrElse(Set.empty[VertexId]))
				}

				// Determine the candidate outgoing links for each burning vertex and source pair
				val eligibleOutLinks = burningVertGraph.mapReduceTriplets[Map[VertexId, Set[VertexId]]](
					// For each outgoing edge, determine which active sources have not
					// yet reached the destination vertex
					edge => (edge.srcAttr._2 &~ edge.dstAttr._1).map(
						source => (edge.srcId, Map(source -> Set(edge.dstId)))
					).toIterator,
					// Gather for each source the set of outgoing links to choose from
					(A, B) => (A.keySet ++ B.keySet).map(
						source => (source, (A.contains(source), B.contains(source)) match {
							case (false, false) => Set.empty[VertexId] // Impossible case
							case (true, false) => A.get(source).get
							case (false, true) => B.get(source).get
							case (true, true) => A.get(source).get ++ B.get(source).get
						})
					).toMap,
					// Limit the selection to burning vertices
					Some((burningVerts, EdgeDirection.Out))
				)
				// Select links to burn
				val newBurningVertsUngrouped = eligibleOutLinks.flatMap {
					case (vtx, options) => options.toList.flatMap {
						case (src, outLinks) => {
							val numBurns = outLinkRandom(i + 31 * vtx + 31 * 31 * src)
							selectRandomLinks(numBurns, outLinks, i + 31 * vtx + 31 * 31 * src).map { (_, Set(src)) }
						}
					}
				}
				val newBurningVerts = g.vertices.aggregateUsingIndex[Set[VertexId]](
					newBurningVertsUngrouped, (A, B) => A ++ B
				).cache()

				// Merge the newly burning vertices into the graph
				val oldG = g
				g = g.outerJoinVertices(newBurningVerts) {
					(_, oldSources, newSources) =>
						if (newSources.isDefined) oldSources ++ newSources.get
						else oldSources
				}
				// Materialize the new graph and release the old graph
				g.vertices.count()
				oldG.edges.unpersist(blocking = false)
				oldG.unpersistVertices(blocking = false)

				newBurningVerts
			}

			// Select incoming links and burn them
			val newInLinks = {
				// Merge information on burning vertices into the graph
				val burningVertGraph = g.outerJoinVertices(burningVerts) {
					(_, data, burningOpt) => (data, burningOpt.getOrElse(Set.empty[VertexId]))
				}

				// Determine the candidate incoming links for each burning vertex and source pair
				val eligibleInLinks = burningVertGraph.mapReduceTriplets[Map[VertexId, Set[VertexId]]](
					// For each incoming edge, determine which active sources have not
					// yet reached the destination vertex
					edge => (edge.dstAttr._2 &~ edge.srcAttr._1).map(
						source => (edge.dstId, Map(source -> Set(edge.srcId)))
					).toIterator,
					// Gather for each source the set of incoming links to choose from
					(A, B) => (A.keySet ++ B.keySet).map(
						source => (source, (A.contains(source), B.contains(source)) match {
							case (false, false) => Set.empty[VertexId] // Impossible case
							case (true, false) => A.get(source).get
							case (false, true) => B.get(source).get
							case (true, true) => A.get(source).get ++ B.get(source).get
						})
					).toMap,
					// Limit the selection to burning vertices
					Some((burningVerts, EdgeDirection.In))
				)
				// Select links to burn
				val newBurningVertsUngrouped = eligibleInLinks.flatMap {
					case (vtx, options) => options.toList.flatMap {
						case (src, inLinks) => {
							val numBurns = outLinkRandom(i + 31 * src + 31 * 31 * vtx)
							selectRandomLinks(numBurns, inLinks, i + 31 * src + 31 * 31 * vtx).map { (_, Set(src)) }
						}
					}
				}
				val newBurningVerts = g.vertices.aggregateUsingIndex[Set[VertexId]](
					newBurningVertsUngrouped, (A, B) => A ++ B
				).cache()

				// Merge the newly burning vertices into the graph
				val oldG = g
				g = g.outerJoinVertices(newBurningVerts) {
					(_, oldSources, newSources) =>
						if (newSources.isDefined) oldSources ++ newSources.get
						else oldSources
				}
				oldG.edges.unpersist(false)
				oldG.unpersistVertices(false)

				newBurningVerts
			}

			// Update the burning vertex list
			val oldBurningVerts = burningVerts
			burningVerts = g.vertices.aggregateUsingIndex[Set[VertexId]](newOutLinks.union(newInLinks), (A, B) => A ++ B).cache()
			oldBurningVerts.unpersist(false)
			newOutLinks.unpersist(false)
			newInLinks.unpersist(false)

			// Update the final edge list
			val oldEdgeList = edgeList
			edgeList = g.vertices.aggregateUsingIndex[Set[VertexId]](edgeList.union(burningVerts), (A, B) => A ++ B).cache()
			oldEdgeList.unpersist()

			i = i + 1
		}

		// Merge the new edges into the original graph
		val graphEdges = if (isDirected) {
			g.edges.union(edgeList.flatMap {
				case (vid, sources) => sources.map(Edge(_, vid, 1))
			})
		} else {
			g.edges.union(edgeList.flatMap {
				case (vid, sources) => sources.flatMap(source => List(Edge(source, vid, 1), Edge(vid, source, 1)))
			})
		}
		Graph[Boolean, Int](g.vertices.mapValues(_ => false), graphEdges, false)
	}

	/**
	 * Convert a graph to the output format of this job.
	 *
	 * @return a GraphXJobOutput object representing the job result
	 */
	override def makeOutput(graph: Graph[Boolean, Int]) =
		new GraphXJobOutput(graph.collectNeighborIds(EdgeDirection.Out).map {
			v => s"${v._1} ${v._2.mkString(" ")}"
		}.cache())

	/**
	 * @return name of the GraphX job
	 */
	override def getAppName: String = "Forest Fire Model"

	/**
	 * @return true iff the input is valid
	 */
	override def hasValidInput: Boolean = evoParam != null &&
		evoParam.getPRatio > 0.0 && evoParam.getPRatio <= 1.0 &&
		evoParam.getRRatio > 0.0 && evoParam.getRRatio <= 1.0
}
