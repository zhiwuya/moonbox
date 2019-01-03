package moonbox.grid.deploy


import java.io.File
import java.util.Date

import akka.actor.ActorRef
import moonbox.common.{MbConf, MbLogging}
import moonbox.grid.deploy.DeployMessages.DriverStateChanged
import moonbox.grid.deploy.master.DriverState
import moonbox.grid.deploy.worker.LaunchUtils
import org.apache.spark.launcher.{Launcher, SparkAppHandle, SparkLauncher}

private[deploy] class DriverRunner(
	conf: MbConf,
	val driverId: String,
	val desc: DriverDescription,
	val worker: ActorRef,
	val submitDate: Date) extends Serializable with MbLogging {

	@transient private var sparkAppHandle: SparkAppHandle = _
	@transient private var process: Process = _

	def start() = {
		new Thread("DriverRunner for " + driverId) {
			override def run(): Unit = {
				try {
					val launcher = new Launcher()
					// redirect log
					LaunchUtils.getLogsDirectory.foreach { dir =>
						launcher.redirectOutput(new File(dir + File.separator + driverId + ".log"))
					}
					launcher
						.setAppName(driverId)
						.setMaster(desc.master)
					if (desc.deployMode.isDefined) {
						launcher.setDeployMode(desc.deployMode.get)
					}
					if (sys.env.get("SPARK_HOME").isDefined) {
						launcher.setSparkHome(sys.env("SPARK_HOME"))
					}
					launcher
						.setMainClass(desc.mainClass)
						.addAppArgs(desc.toAppArgs:_*)
						.setVerbose(false)
						.setAppResource(desc.appResource)
					desc.toConf.foreach { case (k, v) =>
						launcher.setConf(k, v)
					}

					LaunchUtils.getRuntimeJars().foreach { launcher.addJar }

					sparkAppHandle = launcher.startApplication(new SparkAppHandle.Listener {

						override def infoChanged(handle: SparkAppHandle): Unit = {
						}

						override def stateChanged(handle: SparkAppHandle): Unit = {
							logInfo(handle.getState.toString)
							val state = handle.getState match {
								case SparkAppHandle.State.UNKNOWN | SparkAppHandle.State.LOST =>
									DriverState.UNKNOWN
								case SparkAppHandle.State.CONNECTED =>
									DriverState.CONNECTED
								case SparkAppHandle.State.SUBMITTED  =>
									DriverState.SUBMITTED
								case SparkAppHandle.State.RUNNING =>
									DriverState.RUNNING
								case SparkAppHandle.State.FAILED =>
									DriverState.FAILED
								case SparkAppHandle.State.KILLED =>
									DriverState.KILLED
								case SparkAppHandle.State.FINISHED =>
									DriverState.FINISHED
							}
							worker ! DriverStateChanged(driverId, state, Option(handle.getAppId), None)
						}
					})
					process = launcher.process
				} catch {
					case e: Exception =>
						logError("Launch cluster driver failed.", e)
						worker ! DriverStateChanged(driverId, DriverState.ERROR, None, Some(e))
				}
			}
		}.start()
	}

	def kill() = {
		val appId = if (sparkAppHandle != null && sparkAppHandle.getAppId != null) {
			sparkAppHandle.getAppId
		} else "<unknown>"
		logInfo(s"Killing application with id: $appId.")
		if (sparkAppHandle != null) {
			try {
				if (process != null) { process.destroy() }
				sparkAppHandle.stop()
			} catch {
				case e: Exception =>
					logWarning(s"Kill application with id: $appId failed." + e.getMessage)
			}
		} else {
			logWarning(s"SparkAppHandle is null, driver id is $driverId ")
		}
	}

}