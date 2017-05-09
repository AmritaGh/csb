package edu.msstate.dasi.csb

import org.apache.log4j.{Level, Logger}
import org.apache.spark.graphx.Graph

import scala.util.Random

object Benchmark {

  val versionString = "0.2-DEV"

  def main(args: Array[String]): Unit = {

    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    val parser = new OptionParser("csb", versionString, Config())

    parser.parse(args) match {
      case Some(config) =>
        if ( config.debug ) {
          Logger.getLogger("org").setLevel(Level.DEBUG)
          Logger.getLogger("akka").setLevel(Level.DEBUG)
        }

        val factory = new ComponentFactory(config)

        config.mode match {
          case "seed" => run_seed(config, factory)
          case "synth" => run_synth(config, factory)
          case "veracity" => run_veracity(config, factory)
          case "workload" => run_workload(config, factory)
        }
      case None => sys.exit(1)
    }
  }

  private def run_seed(config: Config, factory: ComponentFactory): Boolean = {
    val logAug = new log_Augment()
    logAug.run(config.alertLog, config.connLog, config.augLog)

    val seed = Util.time( "Log to graph", {
      val seed = DataParser.logToGraph(config.augLog, config.partitions)
      println("Vertices #: " + seed.numVertices + ", Edges #: " + seed.numEdges)
      seed
    } )

    Util.time( "Seed distributions", new DataDistributions(config.augLog) )

    Util.time("Save seed graph", factory.getSaver.saveGraph(seed, config.seedGraphPrefix, overwrite = true))

    factory.getTextSaver match {
      case Some(textSaver) =>
        Util.time( "Save seed graph as text", textSaver.saveAsText(seed, config.seedGraphPrefix, overwrite = true) )
      case None =>
    }

    true
  }

  private def run_metrics(metrics: Array[Veracity], seed: Graph[VertexData, EdgeData], synth: Graph[VertexData, EdgeData]): Unit = {
    for (metric <- metrics) {
      val metricName = metric.getClass.getSimpleName.split("\\$").last
      val veracity = Util.time(s"$metricName", metric(seed, synth))
      println(s"$metricName: $veracity")
    }
  }

  private def run_synth(config: Config, factory: ComponentFactory): Boolean = {

    val seed = Util.time( "Load seed graph", {
      val seed = factory.getLoader.loadGraph(config.seedGraphPrefix, config.partitions)
      println("Vertices #: " + seed.numVertices + ", Edges #: " + seed.numEdges)
      seed
    } )

    val seedDists = new DataDistributions(config.augLog)

    val synth = factory.getSynthesizer.synthesize(seed, seedDists, !config.skipProperties)

    Util.time( "Save synth graph", factory.getSaver.saveGraph(synth, config.synthGraphPrefix, overwrite = true))

    factory.getTextSaver match {
      case Some(textSaver) =>
        Util.time( "Save synth graph as text", textSaver.saveAsText(seed, config.synthGraphPrefix, overwrite = true) )
      case None =>
    }

    run_metrics(factory.getMetrics, seed, synth)

    true
  }

  private def run_veracity(config: Config, factory: ComponentFactory): Boolean = {
    val loader = factory.getLoader

    val seed = Util.time( "Load seed graph", loader.loadGraph(config.seedGraphPrefix, config.partitions) )

    val synth = Util.time( "Load synth graph", loader.loadGraph(config.synthGraphPrefix, config.partitions) )

    run_metrics(factory.getMetrics, seed, synth)

    true
  }

  private def run_workload(config: Config, factory: ComponentFactory): Boolean = {
    val loader = factory.getLoader

    val graph = Util.time( "Load graph", loader.loadGraph(config.graphPrefix, config.partitions) )

    val workloadEngine = factory.getWorkloadEngine
    val workloads = config.workloads

    val srcWorkloads = Seq("bfs", "closeness-centrality", "sssp")

    val all = workloads.contains("all")

    if ( all || workloads.contains("count-vertices") ) Util.time("Count vertices", workloadEngine.countVertices(graph))
    if ( all || workloads.contains("count-edges") ) Util.time("Count edges", workloadEngine.countEdges(graph))

    if ( all || workloads.contains("degree") ) Util.time("Degree", workloadEngine.degree(graph))
    if ( all || workloads.contains("in-degree") ) Util.time("In-degree", workloadEngine.inDegree(graph))
    if ( all || workloads.contains("out-degree") ) Util.time("Out-degree", workloadEngine.outDegree(graph))

    if ( all || workloads.contains("pagerank") ) {
      Util.time("PageRank", workloadEngine.pageRank(graph))
    }

    if ( all || workloads.contains("neighbors") ) Util.time("Neighbors", workloadEngine.neighbors(graph))
    if ( all || workloads.contains("in-neighbors") ) Util.time("In-neighbors", workloadEngine.inNeighbors(graph))
    if ( all || workloads.contains("out-neighbors") ) Util.time("Out-neighbors", workloadEngine.outNeighbors(graph))

    if ( all || workloads.contains("in-edges") ) Util.time("Degree", workloadEngine.inEdges(graph))
    if ( all || workloads.contains("out-edges") ) Util.time("In-degree", workloadEngine.outEdges(graph))

    if ( all || workloads.contains("connected-components") ) {
      Util.time("Connected Components", workloadEngine.connectedComponents(graph))
    }

    if ( all || workloads.contains("triangle-count") ) {
      Util.time("Triangle Counting", workloadEngine.triangleCount(graph))
    }

    if ( all || workloads.contains("strongly-connected-components") ) {
      Util.time("Strongly Connected Components", workloadEngine.stronglyConnectedComponents(graph, config.iterations))
    }

    if ( all || workloads.contains("betweenness-centrality") ) {
      Util.time("Betweenness Centrality", workloadEngine.betweennessCentrality(graph, config.iterations))
    }

    if ( all || workloads.intersect(srcWorkloads).nonEmpty ) {
      var srcVertex = config.srcVertex

      if (srcVertex == 0L) {
        println("Source not specified, extracting random ID from the input graph...")

        srcVertex = graph.vertices.zipWithIndex()
          .map{ case ((id, _), index) => (index, id) }
          .lookup( (Random.nextDouble * graph.numVertices).toLong)
          .head

        println(s"Source vertex: $srcVertex")
      }

      if ( all || workloads.contains("bfs") ) {
        var dstVertex = config.dstVertex

        if (dstVertex == 0L) {
          println("Destination not specified, extracting random ID from the input graph...")

          dstVertex = graph.vertices.zipWithIndex()
            .map{ case ((id, _), index) => (index, id) }
            .lookup( (Random.nextDouble * graph.numVertices).toLong)
            .head

          println(s"Destination vertex: $dstVertex")
        }

        Util.time("Breadth-first Search", workloadEngine.bfs(graph, srcVertex, dstVertex))
      }

      if ( all || workloads.contains("closeness-centrality") ) {
        Util.time("Closeness Centrality", workloadEngine.closenessCentrality(graph, srcVertex))
      }

      if ( all || workloads.contains("sssp") ) {
        Util.time("Single Source Shortest Path", workloadEngine.sssp(graph, srcVertex))
      }
    }

    if ( all || workloads.contains("subgraph-isomorphism") ) {
      val pattern = Util.time("Load pattern", loader.loadGraph(config.patternPrefix, config.partitions))
      Util.time("Subgraph Isomorphism", workloadEngine.subgraphIsomorphism(graph, pattern))
    }

    true
  }
}
