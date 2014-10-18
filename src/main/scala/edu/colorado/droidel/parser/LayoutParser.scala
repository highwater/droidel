package edu.colorado.droidel.parser

import java.io.File
import java.net.URLClassLoader
import javax.lang.model.SourceVersion

import com.ibm.wala.classLoader.IClass
import edu.colorado.droidel.parser.LayoutParser._
import edu.colorado.droidel.util.Util

import scala.xml.{Node, XML}


object LayoutParser {
  private val DEBUG = false
  // special ID associated with unknown layout
  val UNKNOWN_LAYOUT_ID = -1
}

class LayoutParser extends AndroidParser {    
  
  /** @return (map from view names to view id's), (map from layout names to layout id's), (map from string names to actual strings) */
  private def makeResourceMaps(appDir : File, binDir : File) : (Map[String,Int], Map[String,Int], Map[String,String]) = { 
    // some id's are generated at compile time, so we can't figure them out just by looking at res/values/strings.xml
    // instead, we need to dig into the generated bytecodes for R.java and extract the values from there
    // we do this by dynamically loading the R.id class, R.layout class, etc. and reading their fields via reflection
    val binPath = binDir.getAbsolutePath()

    // get all classes corresponding to auto-generated resource files -- R$id, R$layout, etc. we need to do this
    // because these files may not only be in the app package. if the app uses libraries with layouts, there 
    // will be generated resource files for the libraries as well. we don't want to miss these
    val rFiles = Util.getAllFiles(binDir, f => f.getName().startsWith("R$") && f.getName().endsWith(".class"))
    if (rFiles.isEmpty) println("Warning: Can't find R$id.class, R$layout.class, etc. This app is likely obfuscated.")
    
    val binURL = binDir.toURI().toURL()                
    val classLoader = URLClassLoader.newInstance(Array(binURL))
    
    def makeLayoutFileName(str : String) : String = "R$" + str + ".class"
    
    def makeMap[T](layoutFileName : String) : Map[String,T] =  
      rFiles.foldLeft (Map.empty[String,T]) ((m, f) => if (f.getName() == layoutFileName) {        
        val className = f.getAbsolutePath().stripPrefix(s"$binPath${File.separator}").stripSuffix(".class").replace(File.separatorChar, '.')
        try {
          val clazz = classLoader.loadClass(className)
          if (DEBUG) println(s"Loaded class file $clazz; reading constants")
          clazz.getDeclaredFields().foldLeft (m) ((m, f) => {
            val name = f.getName()
            val intVal = f.get(f.getType()).asInstanceOf[T] 
            m + (name -> intVal)
          })
        } catch {
          case e : ClassNotFoundException => 
            if (DEBUG) println(s"Error: couldn't load $className")
            m
          case e : Throwable => throw e
        }
      } else m)
    
    def makeStringMap() : Map[String,String] = {
      val valuesDir = new File(s"$appDir/res/values/")
      if (valuesDir.exists()) {
        valuesDir.listFiles().foldLeft (Map.empty[String,String]) ((m, f) => {
          assert(f.getName().endsWith(".xml"), s"Expected XML file, but found ${f.getAbsolutePath()}")
          val valueXMLFile = XML.loadFile(f)
          (valueXMLFile \\ "string").foldLeft (m) ((m, e) => m + (e.attribute("name").head.text -> e.text)) 
        })
        
      } else Map.empty[String,String]
    }
    
    // extend an existing ID and layout map with statically declared ID's from public.xml
    def extendIdAndLayoutMaps(idMap : Map[String,Int], layoutMap : Map[String,Int]) : (Map[String,Int], Map[String,Int]) = {
      val publicDir = new File(s"$appDir/res/values/public.xml")
      def hexStrToDecimalInt(hexStr : String) : Int = {
        val ZERO_X = "0x"
        require(hexStr.startsWith(ZERO_X))
        // Java doesn't like parsing hexes that start with 0x. strip it out
        val stripped = hexStr.replace(ZERO_X, "")
        val radix = 16 // this is a hex value, so use base 16
        Integer.parseInt(stripped, radix)
      } 
      
      if (publicDir.exists()) {
        val publicXMLFile = XML.loadFile(publicDir)
        (publicXMLFile \\ "public").foldLeft (idMap, layoutMap) ((pair, e) => {
          val (idMap, layoutMap) = pair
          val typ = e.attribute("type").head.text
          val name = e.attribute("name").head.text           
          val id = hexStrToDecimalInt(e.attribute("id").head.text)
          if (typ == "layout") (idMap, layoutMap + (name -> id))
          else if (typ == "id") (idMap + (name -> id), layoutMap)
          else pair
        })
      } else (idMap, layoutMap)
    }
   
    val strMap = makeStringMap
    val (idMap, layoutMap) = extendIdAndLayoutMaps(makeMap[Int](makeLayoutFileName("id")), 
                                                   makeMap[Int](makeLayoutFileName("layout")))
    (idMap, layoutMap, strMap)        
  }
  
  // TODO: handle these in some way?
  val NO_PARSE = Set("styles.xml", "strings.xml", "arrays.xml")

  var tmpNameCounter = 0
  val FAKE = "__fake"
  def mkFakeName : String = { tmpNameCounter += 1; FAKE + tmpNameCounter }
  def isFake(name : String) : Boolean = name.startsWith(FAKE)
  def uniquifyString(str : String) = { tmpNameCounter += 1; s"$str$tmpNameCounter" }

  /** @return (manifest representation, resources map) */
  def parseAndroidLayout(appDir : File, binDir : File, manifest : AndroidManifest, layoutIdClassMap : Map[Int,Set[IClass]]) : Map[IClass,Set[LayoutElement]] = {
    require(appDir.isDirectory())

    val NO_CLASS : IClass = null
    
    if (layoutIdClassMap.isEmpty) {
      println("Warning: layoutIdClassMap is empty")
      if (DEBUG) sys.error("Empty layoutIdClass map is nonsensical. Exiting")
    }
        
    val (idMap, layoutMap, strMap) = makeResourceMaps(appDir, binDir) 
    
    // TODO: parse XML other than res/layout?
    val layoutDir = new File(s"${appDir}/res/layout")
    if (!layoutDir.exists()) {
      println(s"Warning: Couldn't find layout directory ${layoutDir.getAbsolutePath}. This is possible, but not expected")
      return Map.empty[IClass,Set[LayoutElement]]
    }

    def stripLayoutPrefix(str : String) : String = str.replace("@layout/", "")
    def stripIdPrefix(str : String) : String = str.replace("@+id/", "").replace("@id/", "").replace("@*", "")
    def getString(str : String, strMap : Map[String,String]) : String = {      
      val idTag = "@string/"
      def stripStrPrefix(str : String) : String = str.replace(idTag, "")
      if (str.contains(idTag)) strMap(stripStrPrefix(str)) // string variable -- strip tag lookup in the string map
      else str.replace("\"", "") // string literal, strip quotes and return as-is
    }    
    
    val resourcesMap = Util.getAllFiles(layoutDir, f => f.getName().endsWith(".xml") && !NO_PARSE.contains(f.getName()))
      .foldLeft (Map.empty[IClass,Set[LayoutElement]]) ((map, xmlFile) => {
      val declFile = xmlFile.getName()
      if (DEBUG) println(s"Parsing resource XML file $declFile")       

      // TODO: support these. see http://www.curious-creature.org/2009/02/25/android-layout-trick-2-include-to-reuse/
      def isUnsupportedConstruct(label : String) : Boolean = {
        val unsupported = List("requestFocus", "merge")
        val res = unsupported.contains(label)
        if (res) println(s"Warning: layout file $declFile uses unsupported construct $label")
        res
      }
      
      @annotation.tailrec
      def parseLayoutElementsRec(worklist: Seq[Node], layoutElems : Set[LayoutElement] = Set.empty[LayoutElement]) : Set[LayoutElement] = worklist match {
        case node :: worklist =>
          val newViews = if (node.label.startsWith("#") || isUnsupportedConstruct(node.label)) layoutElems else {
            val (id, idStr) = getAndroidPrefixedAttrOption(node, "id") match {
              case Some(id) => 
                val stripped = stripIdPrefix(id) 
                val parsedId = idMap.getOrElse(stripped, -1) match {
                  case -1 =>
                    // this happens for some framework-defined id's that are invisible to us, such as @android:id/list
                    // this id is defined in a file android.R.id.list.class that we can't see
                    if (DEBUG) println(s"Couldn't find id corresponding to $stripped")
                    None
                  case id => Some(id)
                }
                (parsedId, uniquifyString(stripped))
              case None => (None, mkFakeName)
            }
            val name = getAndroidPrefixedAttrOption(node, "name") match {
              case Some(name) => name
              case None =>
                // try getting the class instead of the name
                getAndroidPrefixedAttrOption(node, "class") match {
                  case Some(name) => stripLayoutPrefix(name)
                  case None =>
                    // use the string identifier of id (if there is one) -- it's often descriptive of what the element is and vastly
                    // increases the readability of the stubs
                    idStr
                }               
            }
            
            // variant of name that is a valid Java identifier
            val checkedName = if (SourceVersion.isName(idStr)) idStr else mkFakeName

            try {
              val newElem = if (node.label == "fragment") {
                assert(!isFake(name), s"Expected fragment name, but instead got fake name. Node is $node")
                val typ = node.attribute("class") match {
                  case Some(typ) => typ.head.text
                  case None =>
                    // sanity check--make sure the name looks like it could be a type
                    assert(!name.contains(':'), s"$name in $declFile does not look like a Fragment type")
                    name
                }
                new LayoutFragment(typ, declFile, checkedName, id)
              } else if (node.label == "include") {
                val layoutFile = node.attribute("layout") match {
                  case Some(layout) => s"${stripLayoutPrefix(layout.head.text)}.xml"
                  case None => ""
                }
                new LayoutInclude(layoutFile, id)
              } else {
                val text = getAndroidPrefixedAttrOption(node, "text") match {
                  case Some(text) => Some(getString(text, strMap))
                  case None => None
                }
                val onClick = getAndroidPrefixedAttrOption(node, "onClick")
                new LayoutView(node.label, declFile, checkedName, id, text, onClick)
              }
              layoutElems + newElem
            } catch {
              case e : Throwable =>
                if (DEBUG) {
                  println(s"Error while processing $node")
                  throw e
                }
                layoutElems
            }
          }
          parseLayoutElementsRec(node.descendant ++ worklist, newViews)
        case Nil => layoutElems
      }
      
      val xml = XML.loadFile(xmlFile)
      val layoutElems = parseLayoutElementsRec(List(xml))
      
      val declFileName = declFile.stripSuffix(".xml")
      if (layoutMap.contains(declFileName)) {
        val layoutId = layoutMap(declFileName)
        
        // absence of a mapping for key layoutId here just means that we found a declared layout,
        // but we did not find any classes that use that layout. this is a common situation,
        // especially when apps use libraries that declare many layouts
        val layoutClasses = layoutIdClassMap.getOrElse(layoutId, Set.empty[IClass])
        // for some classes, we may not have been able to resolve what layout they are associated with
        // find these classes and conservatively associate them with this particular layout
        val unknownLayoutClasses = if (layoutIdClassMap.contains(UNKNOWN_LAYOUT_ID)) {
          layoutIdClassMap(UNKNOWN_LAYOUT_ID)  
        } else Set.empty[IClass]

        val allClasses = layoutClasses ++ unknownLayoutClasses
        if (allClasses.isEmpty)
          // hack -- map the layout elems to know class so we can resolve their usage with <include>'s later
          map + (NO_CLASS -> layoutElems)
        else
          // associate known and unknown classes with this layout
          allClasses.foldLeft (map) ((map, layoutClass) => map + (layoutClass -> layoutElems))
      } else {
        if (DEBUG) println(s"Warning: couldn't find ID for $declFile")
        map
      }
    })
    
    if (resourcesMap.isEmpty) {
      println("Warning: resources map empty")
      if (DEBUG) sys.error("Empty resources map is nonsensical. Exiting")
    } 

    def resolveIncludes(resourcesMap : Map[IClass,Set[LayoutElement]]) = {
      resourcesMap.map(pair => {
        val (clazz, resources) = pair
        val newResources = resources.map(resource => resource match {
          case include : LayoutInclude =>
            // TODO: I don't think this is quite right; we want the top-level XML layout component declared in the file
            // with the name declFile. Currently, componenets don't know if they are nested or top-level
            resourcesMap.values.flatten.find(e => e != include && e.declFile == include.declFile) match {
              case Some(resolvedInclude) => resolvedInclude
              case None =>
                println(s"Warning: couldn't resolve include $include")
                include
            }
          case e => e
        })
        (clazz, newResources)
      })
    }

    resolveIncludes(resourcesMap) - NO_CLASS
  }

}
