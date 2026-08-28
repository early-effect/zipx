package zipx.core

import zio.json.*

enum MemberProbe derives JsonCodec:
  case FirstPublish, JsOnly, BinaryBreak, Clean

enum BumpStatus derives JsonCodec:
  case Ok, Missing, Undersized, OverBump, NewMemberDirty

final case class ModverReportRow(
    identity: String,
    label: String,
    from: String,
    written: String,
    suggested: String,
    constructor: String,
    kind: BumpKind,
    mimaRan: Boolean,
    status: BumpStatus,
) derives JsonCodec

final case class ModverReport(rows: List[ModverReportRow]) derives JsonCodec

object ModverReport:
  val RelPath: String = "target/zipx-modver-report.json"

  def parse(json: String): Either[String, ModverReport] =
    json.fromJson[ModverReport].left.map(err => s"modver report: $err")

  def render(report: ModverReport): String = report.toJson
end ModverReport

/** Object the Graph publish task reads. Keys are module ids; values are scalaBinaryVersion strings still Missing. */
final case class ModverPublishFile(missing: Map[String, List[String]]) derives JsonCodec

object ModverPublishFile:
  val RelPath: String        = "target/zipx-modver-publish.json"
  val ModulesRelPath: String = "target/zipx-modver-modules.json"

  val empty: ModverPublishFile = ModverPublishFile(Map.empty)

  def parse(json: String): Either[String, ModverPublishFile] =
    json.fromJson[ModverPublishFile].left.map(err => s"modver publish file: $err")

  def render(file: ModverPublishFile): String = file.toJson

  def modulesJson(file: ModverPublishFile): String =
    file.missing.keys.toList.sorted.toJson
end ModverPublishFile
