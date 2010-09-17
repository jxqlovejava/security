require "buildr/xmlbeans"
require "buildr/cobertura"
require "buildr_bnd"

# Keep this structure to allow the build system to update version numbers.
VERSION_NUMBER = "1.0.18-SNAPSHOT"

require "dependencies.rb"
require "repositories.rb"

desc "Security"
define "security" do
  project.version = VERSION_NUMBER
  project.group = "org.intalio.security"
  
  compile.options.target = "1.5"

  desc "Security Framework"
  define "api" do
    compile.with CAS_CLIENT, DOM4J, CASTOR, LOG4J, SLF4J, SPRING[:core], XERCES, OPENSSO_CLIENT_SDK, SERVLET_API

    test.exclude "*BaseSuite"
    test.exclude "*FuncTestSuite"
    test.exclude "*LDAPAuthenticationTest*"
    test.exclude "*MultipleOuTest*"
	test.exclude "*MultipleOuSpecifyTest*"
    test.exclude "*LDAPRBACProviderTest*"
	test.exclude "*MultipleOuMixSpecifyTest*"
    test.with JAXEN, XMLUNIT, INSTINCT
    
    read_m = ::Buildr::Packaging::Java::Manifest.parse(File.read(_("src/main/osgi/META-INF/MANIFEST.MF"))).main
    read_m["Copyright"]="Intalio, Inc 2010"
    read_m["Bundle-Version"]=VERSION_NUMBER
    package(:jar).with :manifest => read_m

    #source bundle
    symbname="#{project.group}.#{project.name}"
    src_man = {"Manifest-Version" => "1.0",
"Bundle-ManifestVersion"=> "2",
"Bundle-SymbolicName" => "#{symbname}.source",
"Bundle-Name" => "#{symbname} source code",
"Bundle-Vendor" => "Intalio, Inc.",
"Bundle-Version"=>VERSION_NUMBER,
"Eclipse-SourceBundle"=>"#{symbname};version=\"#{VERSION_NUMBER}\""}
    package(:jar, :classifier=>'sources').exclude(compile.target.to_s).with(:manifest=>src_man).tap do |path|
      path.include _('src/main/java/org')
    end
    

  end
  
  desc "Security Web-Service Common Library"
  define "ws-common" do
    compile.with project("api"), AXIOM, AXIS2, SLF4J, SPRING[:core], STAX_API

    read_m = ::Buildr::Packaging::Java::Manifest.parse(File.read(_("src/main/osgi/META-INF/MANIFEST.MF"))).main
    read_m["Copyright"]="Intalio, Inc 2010"
    read_m["Bundle-Version"]=VERSION_NUMBER
    package(:jar).with :manifest => read_m

    #source bundle
    symbname="#{project.group}.#{project.name}"
    src_man = {"Manifest-Version" => "1.0",
"Bundle-ManifestVersion"=> "2",
"Bundle-SymbolicName" => "#{symbname}.source",
"Bundle-Name" => "#{symbname} source code",
"Bundle-Vendor" => "Intalio, Inc.",
"Bundle-Version"=>VERSION_NUMBER,
"Eclipse-SourceBundle"=>"#{symbname};version=\"#{VERSION_NUMBER}\""}
    package(:jar, :classifier=>'sources').exclude(compile.target.to_s).with(:manifest=>src_man).tap do |path|
      path.include _('src/main/java/org')
    end


#    package(:bundle).tap do |bnd|
#      bnd['Import-Package'] = "*"
#      bnd['Export-Package'] = "org.intalio.tempo.security.ws.*;version=#{version}"
      #bnd['Include-Resource'] = "../src/main/osgi"
      #bnd['DynamicImport-Package'] = "*"
#    end

  end
  
  desc "Security Web-Service Client"
  define "ws-client" do
    compile.with projects("api", "ws-common"),AXIOM, AXIS2, SLF4J, STAX_API, SPRING[:core]
    test.with APACHE_COMMONS[:httpclient], APACHE_COMMONS[:codec], CASTOR, LOG4J, SUNMAIL, XERCES, WS_COMMONS_SCHEMA, WSDL4J, WOODSTOX, CAS_CLIENT, INSTINCT

    # Remember to set JAVA_OPTIONS before starting Jetty
    # export JAVA_OPTIONS=-Dorg.intalio.tempo.configDirectory=/home/boisvert/svn/tempo/security-ws2/src/test/resources
    
    # require live Axis2 instance
    if ENV["LIVE"] == 'yes'
      LIVE_ENDPOINT = "http://localhost:8080/axis2/services/TokenService"
    end
    
    if defined? LIVE_ENDPOINT
      test.using :properties => 
        { "org.intalio.tempo.security.ws.endpoint" => LIVE_ENDPOINT,
          "org.intalio.tempo.configDirectory" => _("src/test/resources") }
    end

    read_m = ::Buildr::Packaging::Java::Manifest.parse(File.read(_("src/main/osgi/META-INF/MANIFEST.MF"))).main
    read_m["Copyright"]="Intalio, Inc 2010"
    read_m["Bundle-Version"]=VERSION_NUMBER
    package(:jar).with :manifest => read_m

    package(:jar).tap do |jar|
      jar.with :manifest => read_m, :meta_inf => project("ws-service").path_to("src/main/axis2/*.wsdl")
    end

    #source bundle
    symbname="#{project.group}.#{project.name}"
    src_man = {"Manifest-Version" => "1.0",
"Bundle-ManifestVersion"=> "2",
"Bundle-SymbolicName" => "#{symbname}.source",
"Bundle-Name" => "#{symbname} source code",
"Bundle-Vendor" => "Intalio, Inc.",
"Bundle-Version"=>VERSION_NUMBER,
"Eclipse-SourceBundle"=>"#{symbname};version=\"#{VERSION_NUMBER}\""}
    package(:jar, :classifier=>'sources').exclude(compile.target.to_s).with(:manifest=>src_man).tap do |path|
      path.include _('src/main/java/org')
    end


  end

  desc "Security Web-Service"
  define "ws-service" do
    compile.with projects("api", "ws-common"), AXIOM, AXIS2, SLF4J, SPRING[:core], STAX_API  
    package(:aar).with :libs => [ projects("api", "ws-common"), CASTOR, SLF4J, SPRING[:core], CAS_CLIENT ]

    read_m = ::Buildr::Packaging::Java::Manifest.parse(File.read(_("src/main/osgi/META-INF/MANIFEST.MF"))).main
    read_m["Copyright"]="Intalio, Inc 2010"
    read_m["Bundle-Version"]=VERSION_NUMBER
    package(:jar).with :manifest => read_m

    package(:jar).tap do |jar|
      jar.with :manifest => read_m, :meta_inf => _("src/main/osgi/*.xml")
    end

#    package(:bundle).tap do |bnd|
#      bnd['Import-Package'] = "*"
#      bnd['Export-Package'] = "org.intalio.tempo.security*;version=#{version}"
#      bnd['Include-Resource'] = "../src/main/osgi"
#      bnd['DynamicImport-Package'] = "*"
#    end
  end
  
  desc "Common spring and web related classes"
  define "web-nutsNbolts" do
    libs = AXIS2, APACHE_COMMONS[:lang], INTALIO_STATS, JSON_NAGGIT, JSP_API, LOG4J, SERVLET_API, SLF4J, SPRING[:core], SPRING[:webmvc]
    test_libs = libs + [JUNIT, INSTINCT, SPRING_MOCK, AXIOM, project("ws-client"), STAX_API, WSDL4J, WS_COMMONS_SCHEMA]
    compile.with projects("api"), test_libs

    package :jar
    #source bundle
    symbname="#{project.group}.#{project.name}"
    src_man = {"Manifest-Version" => "1.0",
"Bundle-ManifestVersion"=> "2",
"Bundle-SymbolicName" => "#{symbname}.source",
"Bundle-Name" => "#{symbname} source code",
"Bundle-Vendor" => "Intalio, Inc.",
"Bundle-Version"=>VERSION_NUMBER,
"Eclipse-SourceBundle"=>"#{symbname};version=\"#{VERSION_NUMBER}\""}
    package(:jar, :classifier=>'sources').exclude(compile.target.to_s).with(:manifest=>src_man).tap do |path|
      path.include _('src/main/java/org')
    end

#    package(:bundle, :classifier=>'bundle').tap do |bnd|
#      bnd['Import-Package'] = "*"
#      bnd['Private-Package'] = "org.intalio.tempo.versions;version=#{version}"
#      bnd['Export-Package'] = "org.intalio.tempo.uiframework.versions;version=#{version},org.intalio.tempo.web;version=#{version},org.intalio.tempo.web.controller;version=#{version},org.intalio.tempo.workflow;version=#{version}"
      #bnd['Include-Resource'] = "../src/main/osgi"
      #bnd['DynamicImport-Package'] = "*"
#    end
  end
end
