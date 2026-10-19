package zipx.core

/** A cloud agent environment rendered from this build. `Off` is the default. `docker` is explicit: a Testcontainers
  * dependency does not turn it on.
  */
enum CloudAgent:
  case Off
  case On(docker: Boolean)
