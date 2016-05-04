package debug

object Backtrace {
  def main(args: Array[String]): Unit = {
    level1()
  }

  def level1(): Unit = {
    level2()
  }

  def level2(): Unit = {
    println("Here")
  }
}
