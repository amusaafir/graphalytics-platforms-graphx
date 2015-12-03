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
package nl.tudelft.graphalytics.graphx.bfs

import java.util

import nl.tudelft.graphalytics.domain.GraphFormat
import nl.tudelft.graphalytics.domain.algorithms.BreadthFirstSearchParameters
import nl.tudelft.graphalytics.graphx.{GraphXJobTest, ValidationGraphUtils}
import nl.tudelft.graphalytics.validation.GraphStructure
import nl.tudelft.graphalytics.validation.bfs.{BreadthFirstSearchOutput, BreadthFirstSearchValidationTest}

/**
 * Integration test for BFS job on GraphX.
 *
 * @author Tim Hegeman
 */
class BreadthFirstSearchJobTest extends BreadthFirstSearchValidationTest with GraphXJobTest {

	override def executeUndirectedBreadthFirstSearch(graph: GraphStructure,
			parameters: BreadthFirstSearchParameters): BreadthFirstSearchOutput = {
		val (vertexData, edgeData) = ValidationGraphUtils.undirectedValidationGraphToEdgeList(graph)
		executeBreadthFirstSearch(vertexData, edgeData, false, parameters)
	}

	override def executeDirectedBreadthFirstSearch(graph: GraphStructure,
			parameters: BreadthFirstSearchParameters): BreadthFirstSearchOutput = {
		val (vertexData, edgeData) = ValidationGraphUtils.directedValidationGraphToEdgeList(graph)
		executeBreadthFirstSearch(vertexData, edgeData, true, parameters)
	}

	private def executeBreadthFirstSearch(vertexData : List[String], edgeData : List[String], directed: Boolean,
			parameters: BreadthFirstSearchParameters): BreadthFirstSearchOutput = {
		val bfsJob = new BreadthFirstSearchJob("", "", new GraphFormat(directed), "", parameters)
		val (vertexOutput, _) = executeJob(bfsJob, vertexData, edgeData)
		val outputAsJavaMap = new util.HashMap[java.lang.Long, java.lang.Long](vertexOutput.size)
		vertexOutput.foreach { case (vid, value) => outputAsJavaMap.put(vid, value) }
		new BreadthFirstSearchOutput(outputAsJavaMap)
	}

}
