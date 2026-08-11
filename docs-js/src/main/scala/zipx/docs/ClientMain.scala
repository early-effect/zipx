package zipx.docs

import specular.client.SpecularClient
import zio.*

/** Browser entry: remount every `.interactive` ascent example on the current page. */
object ClientMain extends ZIOAppDefault:

  val pages = Vector(
    MatrixCollapsePage.doc,
    ComposingSbtCommands.doc,
  )

  def run = ZIO.scoped {
    SpecularClient.mountAll(SpecularClient.fromPages(pages*)) *> ZIO.never
  }
end ClientMain
