package tests

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object FileLayout {
  def fromString(
      layout: String,
      root: Path = Files.createTempDirectory("bill"),
      charset: Charset = StandardCharsets.UTF_8
  ): Path = {
    if (!layout.trim.isEmpty) {
      val lines = layout.replaceAllLiterally("\r\n", "\n")
      lines.split("(?=\n/)").foreach { row =>
        row.stripPrefix("\n").split("\n", 2).toList match {
          case path :: contents :: Nil =>
            val file =
              path.stripPrefix("/").split("/").foldLeft(root)(_ resolve _)
            val parent = file.getParent
            Files.createDirectories(parent)
            Files.deleteIfExists(file)
            Files.write(
              file,
              contents.getBytes(charset),
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE
            )
          case els =>
            throw new IllegalArgumentException(
              s"Unable to split argument info path/contents! \n$els"
            )
        }
      }
    }
    root
  }
}
