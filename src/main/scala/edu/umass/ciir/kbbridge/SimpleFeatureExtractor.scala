package edu.umass.ciir.kbbridge

import features.{ Mention2EntityFeatureHasher}
import java.io.{PrintWriter, FileOutputStream, File}
import serial.EntityMentionProtos.{LinkerFeature, ScoredWikipediaEntityFeatures, EntityMentionLinkerFeatures, TacEntityMentionLinkerFeatures}
import edu.umass.ciir.kbbridge.tac.{TacDocumentXmlLoader, TacFileMap, TacQueryUtil}
import text2kb.KnowledgeBaseCandidateGenerator
import util.ConfInfo
import com.google.protobuf.TextFormat
import edu.umass.ciir.kbbridge.data.TacEntityMention


/*
 * Takes a TAC entity mention (query), runs a query and generates annotated results.
 *
 * The mention with candidates and features are serialized to a file on disk.
 *
 * Generates candidates for the query + all detected entities.
 * 
 * For each entity mention, writes the features into XML.
 * 
 *  This is a stripped down neighbor prep, meant to operate at the level
 *  of one query at a time.  It is meant to be run in parallel with no use of
 *  outside resources besides the galago index.
 *  
 */
object SimpleFeatureExtractor {

//  val nlpSteps = Seq(
//
//    ClearSegmenter,
//    // Truecasing??
//    POS1,
//    // LemmaAnnotator,
//    NER1,
//    //FactorieNERComponent,
//    DepParser2,
//    ParseBasedMentionFinding
//  )

  val overwrite = false

  def extractFeatures(mention: TacEntityMention) {

    val docId = mention.docId
    val mentionId = mention.mentionId

    val dir = new File(ConfInfo.serializedFeaturePath)
    if (!dir.exists()) {
      dir.mkdirs()
    }

    val svmFileName = ConfInfo.serializedFeaturePath + File.separator + docId + "_" + mentionId + "_m2eOnly.svm"
    val pw = new PrintWriter(svmFileName)

    val filename = ConfInfo.serializedFeaturePath + File.separator + docId + "_" + mentionId + "_m2eOnly.pbdat"
    val file = new File(filename)
    println("Writing feature file: " + file.getAbsolutePath)
    if (!file.exists() || overwrite) {
      println("Fetching candidates for mention: " + mention.mentionId + " " + mention.docId + " " + mention.entityName)

      val candidates = KnowledgeBaseCandidateGenerator().retrieveCandidates(mention, ConfInfo.maxEntityCandidates)

      val candsWithRank = candidates.zipWithIndex

      val output = new FileOutputStream(file)

      val tacLinkerFeatures = TacEntityMentionLinkerFeatures.newBuilder()
      tacLinkerFeatures.setNodeId(mention.nodeId)
      tacLinkerFeatures.setGroundTruthWikiTitle(mention.groundTruthWikiTitle.getOrElse(""))

      val mentionLinkerFeatures = EntityMentionLinkerFeatures.newBuilder()
      mentionLinkerFeatures.setEntityName(mention.entityName)
      mentionLinkerFeatures.setEntityType(mention.entityType)
      mentionLinkerFeatures.setMentionId(mention.mentionId)
      mentionLinkerFeatures.setSourceDocId(mention.docId)

      println("Extracting features...")
      for ((entity, rank) <- candsWithRank) {
        val m2eFeatures = Mention2EntityFeatureHasher.featuresAsMap(ConfInfo.rankingFeatures, mention, entity, candidates)
        val entityWithFeatures = ScoredWikipediaEntityFeatures.newBuilder()
        entityWithFeatures.setRank(rank + 1)

        entityWithFeatures.setWikipediaId(entity.wikipediaId)
        entityWithFeatures.setWikipediaTitle(entity.wikipediaTitle)
        entityWithFeatures.setScore(entity.score)

        for ((key, value) <- m2eFeatures) {
          val feature = LinkerFeature.newBuilder()
          feature.setKey(key)
          feature.setValue(value)
          entityWithFeatures.addRankingFeatures(feature)
        }
        mentionLinkerFeatures.addCandidates(entityWithFeatures)
        //val svmFeatures = EntityFeaturesToSvmConverter.entityToSvmFormat(mention, entity, m2eFeatures)
       // pw.println(svmFeatures)

      }
      tacLinkerFeatures.setMention(mentionLinkerFeatures)
      tacLinkerFeatures.build().writeTo(output)
      if (false) {
        println(TextFormat .printToString(tacLinkerFeatures) + "\n")
      }
      output.close()
      pw.close()

    } else {
      println("Skipping existing feature file.")
    }
    println("Done writing features...")
  }

//  def annotateQueryDoc(documentId: String) {
//    val fileName = ConfInfo.sourceDir + TacFileMap.docIdToFilenameMap(documentId)
//    val text = TacDocumentXmlLoader.fullTextViaTacSource(fileName)
//    val doc = new Document(text)
//    doc.setName(gDoc.name)
//    for (step <- nlpSteps) {
//      step.process(doc)
//    }
//    val xml = Document2XmlRenderer.xml(doc)
//  }

  def main(args: Array[String]) {

    println("starting feature extractor version 0.6")
    println("args:" + args.toString())
    val targetQuerySet = args.mkString("").split(",").toSet


    //    val trainingQueries = SimpleRankUtil.selectTrainingQueries
    //    val testQueries = SimpleRankUtil.selectTestQueries
    //    val allqueries = trainingQueries ++ testQueries ++ SimpleRankUtil.select2012TestQueries()
    val allqueries = TacQueryUtil.allQueries
    println("Total queries: " + allqueries.size)

    var targetQueries = allqueries
    targetQueries = targetQueries.filter(q => targetQuerySet contains q.mentionId)
    for (q <- targetQueries) {
      extractFeatures(q)
    }
  }

  def filterQueriesForRanking(instances: Seq[TacEntityMention]): Seq[TacEntityMention] = {
    val nonNil = instances.filterNot(_.isNilQuery)
    println("Non-nil: " + nonNil.size)
    nonNil
  }
}