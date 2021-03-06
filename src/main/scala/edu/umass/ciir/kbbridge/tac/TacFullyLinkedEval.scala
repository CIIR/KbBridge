package edu.umass.ciir.kbbridge.tac

import java.io.{File, PrintWriter}
import edu.umass.ciir.kbbridge.eval.{TacMetricsCalculator, TacLinkingEvaluator}
import edu.umass.ciir.kbbridge.data._
import scala.collection.mutable.ListBuffer
import edu.umass.ciir.kbbridge.nil.{NilClusterer, NilPredictorAndClassifierMain}
import scala.xml.XML
import edu.umass.ciir.kbbridge.data.SimpleEntityMention
import scala.Some
import edu.umass.ciir.kbbridge.data.TacEntityMention
import edu.umass.ciir.kbbridge.eval.TacMetricsCalculator.LinkerQueryPrediction
import edu.umass.ciir.util.TextNormalizer
import scala.collection.mutable

/**
 * Created with IntelliJ IDEA.
 * User: jdalton
 * Date: 8/5/13
 * Time: 4:44 PM
 * To change this template use File | Settings | File Templates.
 */
object TacFullyLinkedEval extends App {


  val outputEvalDir = "./" + args(1) + "/" + args(2)
  val outputDirFile = new File(outputEvalDir)
  (outputDirFile.mkdirs())

  // add tac specific output dir
  val tacOutputDir = outputEvalDir + "/tac"
  val rawDir = new File(tacOutputDir)
  (rawDir.mkdirs())

  var maxScore = Double.NegativeInfinity
  var minScore = Double.PositiveInfinity

  eval()




  def eval() {
    val querySet = TacQueryUtil.allQueries
    val queriesByYear = TacQueryUtil.queriesByYear.filter(_._1 equals "2013")
    println("query stats:")
    println("total queries: " + querySet.size)
    val nonNil =  querySet.filterNot(q => q.isNilQuery)
    println("total non-nil queries: " +nonNil.size)
    val summaryWriter = new PrintWriter(outputEvalDir + "/summary")

    for ((year, (annoFile, queries)) <- queriesByYear) {
      val testQueries = queries.filter(q => querySet contains q)

      if (!year.equals("2013")) {
        TacLinkingEvaluator.clearJudgments()
        TacLinkingEvaluator.loadGoldStandardFile(annoFile)

        println("Results for : " + year)
        println("queries: " + testQueries.size)
        val nonNil = testQueries.filterNot(q => q.isNilQuery)
        println("test non-nil queries: " + nonNil.size)
      }
      var numNonMatches = 0
      val rawRanking = mutable.HashMap[String, LinkedMention]()
      val links = for (q <- testQueries) yield {
        val doc = q.docId
        val file = new File(args(0) + File.separatorChar + q.mentionId + ".xml")
        if (file.exists()) {
          val links = entityLinks(doc, file, 500)
          //val links = docMentions(doc, file)
          var matchingLinks = links.filter(links => TextNormalizer.normalizeText(links.mention.entityName) equals TextNormalizer.normalizeText(q.entityName))

//          println("q: " + q.mentionId + " num exact matches: " + matchingLinks.size)
      //    if (matchingLinks.size == 0) {
            matchingLinks ++= links.filter(links => (TextNormalizer.normalizeText(links.mention.entityName.toLowerCase) contains TextNormalizer.normalizeText(q.entityName.toLowerCase())) || (TextNormalizer.normalizeText(q.entityName.toLowerCase()) contains TextNormalizer.normalizeText(links.mention.entityName.toLowerCase())))
           //println("q: " + q.mentionId + " num subset matches: " + matchingLinks.size)

      //    }


          val canonicalMention = if (matchingLinks.size == 0) {
            println("q: " + q + " no matches. ")
            numNonMatches += 1
            new LinkedMention(q, Seq())
          } else {
            resolveMultipleMentions(matchingLinks, q)
          }

          val raw = rawCandidates(doc, file)
          val topRaw = raw.entityLinks.headOption
          if (topRaw != None) {
            val scoreMap = canonicalMention.entityLinks.map(e=> e.wikipediaTitle -> e.score).toMap
            val score = scoreMap(topRaw.get.wikipediaTitle)
            rawRanking += q.mentionId -> new LinkedMention(q, Seq(new ScoredWikipediaEntity(topRaw.get.wikipediaTitle, -1, score , topRaw.get.rank)))
          } else {
            rawRanking += q.mentionId -> new LinkedMention(q, Seq())
          }
          q.mentionId -> canonicalMention
        } else {
          println("file not found for mention: " + file.getAbsolutePath + " "  + q.mentionId + " " + q.docId)
          q.mentionId -> new LinkedMention(q, Seq())
        }
      }
      println("num non-matches " + numNonMatches + " " + (numNonMatches.toDouble / testQueries.size) )
      val linksByMention = links.toMap
      val dir = new File(tacOutputDir + "/" + year)
      dir.mkdirs()
      writeTACResults(testQueries, linksByMention, tacOutputDir + "/" + year + "/", outputEvalDir +  File.separator + year)
      //writeTACResults(testQueries, rawRanking.toMap, tacOutputDir + "/" + year + "/", outputEvalDir +  File.separator + year)

    }

  }

  case class LinkedMention(mention: EntityMention, entityLinks: Seq[ScoredWikipediaEntity])


  def resolveMultipleMentions(matchingLinks: Seq[LinkedMention], originalMention:TacEntityMention): LinkedMention = {
    var bestMention = matchingLinks.head
    if (matchingLinks.size > 1) {
   // println("Resolving multiple matches: " + originalMention)
    var curBestScore = Double.NegativeInfinity
    for (matchingMention <- matchingLinks) {
      val topLink = bestMention.entityLinks.headOption
      topLink match {
        case Some(link) => {
          val topCand = bestMention.entityLinks.head
          if (link.score > curBestScore) {
            bestMention = matchingMention
            curBestScore = topCand.score
          //  println(topCand.wikipediaTitle + " " + topCand.score)
          }

        }
        case _ => {
          println("No top candidate for mention: " + matchingMention.mention.entityName)
        }
      }
    }
      //println(originalMention.mentionId + " " + originalMention.entityName + " " + bestMention.mention.entityName.trim() + " "  + curBestScore)
    }

    bestMention
  }

  def docMentions(docId: String, file: File, candidateLimit: Int = 50) = {
    val entityLinks = XML.loadFile(file) \\ "mention"
    for (e <- entityLinks) yield {
      //println((e \ "name").text)
      val mention = new SimpleEntityMention(docId, "", (e \ "tokenBegin").text +"_"+ (e \ "tokenEnd").text, (e \ "string").text, "")
      val linkedMention = LinkedMention(mention, Seq())
      // println(linkedMention)
      linkedMention
    }
  }

  def entityLinks(docId: String, file: File, candidateLimit: Int = 50) = {
    val entityLinks = XML.loadFile(file) \\ "entitylink"
    for (e <- entityLinks) yield {
      //println((e \ "name").text)
      val mention = new SimpleEntityMention(docId, "", (e \ "mentionId").text, (e \ "name").text, "")
      val candidates = e \\ "candidate" take candidateLimit
      val candidateEntities = for (c <- candidates) yield {
       // println(c)
        val id = (c \ "id").text.trim
        val score = (c \ "score").text.trim.toDouble
        if (score > maxScore) {
          maxScore = score
        }
        if (score < minScore) {
          minScore = score
        }
        val rank =  (c \ "rank").text.trim.toInt
        new ScoredWikipediaEntity(id, -1,score , rank)
      }

      val linkedMention = LinkedMention(mention, candidateEntities)
      // println(linkedMention)
      linkedMention
    }
  }


  def rawCandidates(docId: String, file: File, candidateLimit: Int = 50) = {
    val mention = new SimpleEntityMention(docId, "", "dummy", "dummy", "")

    val candidates = XML.loadFile(file) \\ "rawcandidate"
      val candidateEntities = for (c <- candidates) yield {
        // println(c)
        val id = (c \ "id").text.trim
        val score = (c \ "score").text.trim.toDouble
        val rank =  (c \ "rank").text.trim.toInt
        new ScoredWikipediaEntity(id, -1,score , rank)
      }
      val linkedMention = LinkedMention(mention, candidateEntities)
      // println(linkedMention)
      linkedMention

  }


  def writeTACResults(queries: Seq[TacEntityMention], rerankedResults: Map[String, LinkedMention], tacOutputDirPrefix: String, outputFilePrefix: String) {
    val finalResults = new ListBuffer[LinkerQueryPrediction]

    val rankingWriter = new PrintWriter(outputFilePrefix + ".ranking")

    val relevantRanks = new ListBuffer[Int]()

    for (q <- queries) {
      val results = rerankedResults.get(q.mentionId)



      results match {
        // no results, no wiki results
        case None => finalResults += new LinkerQueryPrediction(q, None, None)
        case Some(results) => {
          if (results.entityLinks.length == 0) {
            println("Results with no top result!?! " + q.mentionId)
            finalResults += new LinkerQueryPrediction(q, None, None)
          } else {

            val trueResult = q.groundTruthWikiTitle
            val foundIdx = trueResult match {
              case Some(tacWikiTitle) => {
                val idx = results.entityLinks.indexWhere(r => r.wikipediaTitle equals tacWikiTitle)
                (idx)
              }
              case None => {
                -1
              }
            }
            relevantRanks += foundIdx
            //            if (!TacQueryUtil.isNilQuery(q) && foundIdx < 0) {
            //              println("Missing in candidate set: " + q)
            //            }


            results.entityLinks.map(r => rankingWriter.println("%s Q0 %s %d %s KL".format(q.mentionId, r.wikipediaTitle, r.rank, "%10.8f".format(r.score))))
            val topResult = results.entityLinks.head
            val tacIdListOpt = IdMap.wikiTitle2TacIdMap.get(topResult.wikipediaTitle)
            tacIdListOpt match {
              case Some(tacIdList) if (tacIdList != "") => {
                // TAC result, return tac ID + wiki title
                finalResults += new LinkerQueryPrediction(q, Some(tacIdList.split(",")), Some(topResult.wikipediaTitle), topResult.score)
              }
              case _ => {
                // Result is returned, but it is a non-tac result
                finalResults += new LinkerQueryPrediction(q, None, Some(topResult.wikipediaTitle), topResult.score)
              }
            }
          }

        }
      }

    }
    writeRelevantDistribution(outputFilePrefix, relevantRanks)
    rankingWriter.close()
    println("\nWriting and Evaluating model: " + outputFilePrefix)
    println("Raw rank-only accuracy:")
    TacMetricsCalculator.evaluateMicroAverageAccuracy(finalResults, outputFilePrefix)

    //   TacPredictionWriter.writePredicted(finalResults, tacOutputDirPrefix + ".wikiprediction" )

    val wikiNilPredictions = NilPredictorAndClassifierMain.wikiOnly(finalResults)

    println("SCORE RANGE:" + minScore + " " + maxScore)
    val thresholds = Array(0.5, 1, 2.5, 3, 3.5)
    for (threshold <- thresholds) {
      println("Threshold:" + threshold)
      val nilPredictions = NilPredictorAndClassifierMain.predictNils(finalResults, threshold)
      val clustered = NilClusterer.clusterNils(nilPredictions)
      TacPredictionWriter.writePredicted(clustered, tacOutputDirPrefix + threshold, maxScore, minScore)
      TacMetricsCalculator.evaluateMicroAverageAccuracy(nilPredictions, outputFilePrefix + "_" + threshold)

    }


  }


  def writeRelevantDistribution(filePrefix: String, relevantRanks: ListBuffer[Int]) {
    val writer = new PrintWriter(filePrefix + ".answer_distribution")
    val counts = relevantRanks.foldLeft(Map[Int, Int]()) {
      (m, c) => m.updated(c, m.getOrElse(c, 0) + 1)
    }
    //val sortedCounts = counts.keySet.toList.sort( (e1, e2) => (e1 < e2))
    for (i <- -1 to 99) {
      writer.println(i + " " + counts.getOrElse(i, 0))
    }
    writer.close
  }

}
