import java.io.File
import edu.colorado.droidel.driver.AndroidAppTransformer
import edu.colorado.droidel.driver.AndroidCGBuilder
import edu.colorado.droidel.driver.AbsurdityIdentifier
import edu.colorado.droidel.constants.DroidelConstants
import edu.colorado.droidel.util.Timer
import edu.colorado.droidel.util.Util

object Regression {
  def main(args: Array[String]) = {
    val testPrefix = "src/test/resources/regression/"
      
    val tests = List("HoistTest1", "HoistTest2", "ProtectedCallback", "ViewLookup", "LifecycleAndInterfaceCallback")

    tests.foreach(test => {
      val testPath = s"$testPrefix$test"
      // TODO: unhardcode this!
      val androidJar = new File("/home/sam/Desktop/AbsurdDroid/android-platforms/android-16/android.jar")

      val droidelOutBinDir = new File(s"${testPath}/${DroidelConstants.DROIDEL_BIN_SUFFIX}")
      // clear Droidel output if it already exists
      if (droidelOutBinDir.exists()) Util.deleteAllFiles(droidelOutBinDir)

      // generate stubs and a specialized harness for the app
      val transformer = new AndroidAppTransformer(testPath, androidJar, 
					          // our tests should have all the library dependencies included, so we don't need JPhantom
                                                  useJPhantom = false,
						  // we don't want to keep the generated source files for the stubs/harness
						  cleanupGeneratedFiles = true)
      transformer.transformApp() // do it

      // make sure Droidel did something
      assert(droidelOutBinDir.exists(), s"No Droidel output found in ${droidelOutBinDir.getAbsolutePath()}")

      // now, build a call graph and points-to analysis with the generated stubs/harness 
      val analysisScope = transformer.makeAnalysisScope(useHarness = true)
      val timer = new Timer()
      timer.start()
      println("Building call graph")
      val walaRes = new AndroidCGBuilder(analysisScope, transformer.harnessClassName, transformer.harnessMethodName).makeAndroidCallGraph
      timer.printTimeTaken("Building call graph")
      
      // walk over the call call graph / points-to analysis and check that they are free of absurdities
      println("Checking for absurdities")
      val absurdities = new AbsurdityIdentifier(transformer.harnessClassName).getAbsurdities(walaRes)
      timer.printTimeTaken("Checking for absurdities") 
      assert(absurdities.isEmpty, s"After harness generation, expected no absurdities for test $test")

      // clean up after ourselves
      Util.deleteAllFiles(droidelOutBinDir)
    })
  }
  
}