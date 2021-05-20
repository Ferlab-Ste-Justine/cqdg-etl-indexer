package ca.cqdg

import scala.util.Properties

package object index {

  def getConfiguration(key: String, default: String): String = {
    Properties.envOrElse(key, Properties.propOrElse(key, default))
  }

}
