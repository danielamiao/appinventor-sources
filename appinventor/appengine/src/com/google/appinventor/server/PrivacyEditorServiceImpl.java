package com.google.appinventor.server;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.hp.hpl.jena.rdf.model.InfModel;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.reasoner.Reasoner;
import com.hp.hpl.jena.reasoner.ReasonerRegistry;
import com.hp.hpl.jena.util.PrintUtil;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;
import com.google.appinventor.server.properties.json.ServerJsonParser;
import com.google.appinventor.server.storage.StorageIo;
import com.google.appinventor.server.storage.StorageIoInstanceHolder;
import com.google.appinventor.shared.properties.json.JSONArray;
import com.google.appinventor.shared.properties.json.JSONObject;
import com.google.appinventor.shared.properties.json.JSONParser;
import com.google.appinventor.shared.properties.json.JSONValue;
import com.google.appinventor.shared.rpc.privacy.PrivacyEditorService;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.appinventor.shared.youngandroid.YoungAndroidSourceAnalyzer;
import com.google.appinventor.shared.youngandroid.YoungAndroidXMLSourceAnalyzer;

public class PrivacyEditorServiceImpl extends OdeRemoteServiceServlet implements PrivacyEditorService {

  //Logging support
  private static final Logger LOG = Logger.getLogger(PrivacyEditorServiceImpl.class.getName());
  
  // Custom Defined Constants
  private static final String BASE_NS = "http://ai2.appinventor.mit.edu/privacyDescription/";
  private static final String TEMPLATE_LOC ="privacy_templates/"; // template location with respective to current classpath
  private static final String AI_NS = "http://dig.csail.mit.edu/2014/PrivacyInformer/appinventor#";
  private static final String COMPONENT_NS = "http://dig.csail.mit.edu/2014/PrivacyInformer/";
  private static final Property aiContains = ResourceFactory.createProperty( AI_NS, "contains");
  private static final Property aiConnectsTo = ResourceFactory.createProperty( AI_NS, "connectsTo");
  private static final Property aiDescription = ResourceFactory.createProperty( AI_NS, "description");
  private static final Resource aiComponentEvent = ResourceFactory.createResource(AI_NS + "ComponentEvent");
  private static final Resource aiComponentMethod = ResourceFactory.createResource(AI_NS + "ComponentMethod");
  private static final Resource aiComponentProperty = ResourceFactory.createResource(AI_NS + "ComponentProperty");
  
  //Declare and Initialize required constants
  private final transient StorageIo storageIo = StorageIoInstanceHolder.INSTANCE;
  private static final JSONParser JSON_PARSER = new ServerJsonParser();
  private static Model model = ModelFactory.createDefaultModel();
  private static Model ontModel = ModelFactory.createDefaultModel();
  private static String project_ns = "";
  
  @Override
  public String getPrivacyTTL(long projectId) {
    // reset model statements
    model.removeAll();
    ontModel.removeAll();
    project_ns = "";
    
    // get templates
    List<String> templates = getTemplates(getClass());
    // reset preview text
    String preview = "";
    // get userId based on projectId
    final String userId = userInfoProvider.getUserId();
    
    // create a unique namespace for this privacy description  based on the project name and email address
    String projectName = storageIo.getProjectName(userId, projectId);
    String userEmail = userInfoProvider.getUserEmail();
    project_ns = BASE_NS + projectName + "_" + userEmail.split("@")[0] + "_" + projectId + "#";
    
    // set the prefixes
    model.setNsPrefix("", project_ns);
    model.setNsPrefix("ai", AI_NS);
    
    String privacyDescriptionURI = project_ns + "me";
    Resource privacyDescription = model.createResource(privacyDescriptionURI).addProperty(RDF.type, ResourceFactory.createResource( AI_NS + "PrivacyDescription"));
    
    // get the project source files 
    List<String> projectFiles = storageIo.getProjectSourceFiles(userId, projectId);
    
    // get a list of all components in the project
    List<String> appComponents = getComponentList(projectFiles, userId, projectId);
    
    // for each component, if it has a template (meaning it's privacy-sensitive), add the component to Jena model and set the appropriate prefix
    for (String component : appComponents) {
      if (templates.contains(component)) {
        privacyDescription.addProperty(aiContains, ResourceFactory.createResource( COMPONENT_NS + component + "#" + component + "Component"));
        model.setNsPrefix(component.toLowerCase(), COMPONENT_NS + component + "#");
        ontModel.read(getClass().getResourceAsStream( TEMPLATE_LOC + component), null, "TTL");
      }
    }
    
    // get blocks logic files
    List<String> xmlFiles = getBlocks(projectFiles, userId, projectId);
    
    // for each XML source file, parse it and get the relationships in it, then process relationships by adding "connectsTo" statements to the model
    for (String file : xmlFiles) {
      ArrayList<ArrayList<ArrayList<String>>> relationships = YoungAndroidXMLSourceAnalyzer.parseXMLSource(file, templates);
      processRelationships(relationships);
    }
    
    // Write the model statements to an out string
    StringWriter out = new StringWriter();
    model.write(out, "TTL");
    //System.out.println(out.toString());
    return out.toString();
  }

  public String getPrivacyHTML(long projectId) {
    // We must first generate the privacy model for this AppInventor
    String privacyModel = getPrivacyTTL(projectId);
    
    // Create a new model with the appinventor and android ontology loaded
    Model aiAndroidModel = ModelFactory.createDefaultModel();
    aiAndroidModel.read(getClass().getResourceAsStream( TEMPLATE_LOC + "appinventor"), null, "TTL");
    aiAndroidModel.read(getClass().getResourceAsStream( TEMPLATE_LOC + "android"), null, "TTL");
    
    // Set up the initial content of the privacy description
    String projectName = storageIo.getProjectName(userInfoProvider.getUserId(), projectId);
    String userEmail = userInfoProvider.getUserEmail();
    String html = "<html><head><style type=\"text/css\">.SEC { border: 1px solid #CCC; width: 99%; margin: 1% auto; background-color: #E6F8E0 } </style></head><body>";
    String title = "<div id=\"privacy-top\" class=\"SEC\"><h2>Privacy Description for " + projectName + "</h2>";
    String intro = "<p>" + projectName + " is an Android mobile application made on the AppInventor platform. " +
                   "The developer can be reached at <a href=\"mailto:" + userEmail + "\">" + userEmail + "</a>.</p>";
    String summary = "<h3>Privacy Summary</h3>";
    String details = "";
    String interactions = "<div class=\"SEC\"><h3>Privacy-sensitive Interactions</h3>";
    
    // select all the components referred to by property "ai:contains"
    StmtIterator iter = model.listStatements(null, aiContains, (RDFNode) null);
    if (iter.hasNext()) {
      summary += "<p> This application contains the following privacy-sensitive components: " +
                "<ul>";
      while (iter.hasNext()) {
        // create a list element for the component
        Resource component = iter.nextStatement().getObject().asResource();
        String compLabel = ontModel.getProperty(component, RDFS.label).getString();
        String compDescription = ontModel.getProperty(component, aiDescription).getString();
        summary += "<li><a href=\"#privacy-" + compLabel.split(" ")[0] + "\">" + compLabel + "</a>, which " + compDescription + ".";
        
        // create a section for detailed annotations of the component
        details += "<div class=\"SEC\"><h3 id=\"privacy-" + compLabel.split(" ")[0] + "\">Details for " + compLabel + "</h3>";
        details += "<ul>";
        details += "<li>" + compLabel + " " + compDescription;
        StmtIterator propIter = ontModel.listStatements(component, null, (RDFNode) null);
        while (propIter.hasNext()) {
          Statement cur = propIter.nextStatement();
          Statement predicateLabel = aiAndroidModel.getProperty(cur.getPredicate().asResource(), RDFS.label);
          
          if (predicateLabel != null) {
            String predicateLabelStr = predicateLabel.getString();
            String objectLabelStr = cur.getObject().toString(); //default label for object is the URI
            if (cur.getObject().isLiteral()) {
              objectLabelStr = cur.getObject().asLiteral().getString();
            } else {
              Statement objectLabel = aiAndroidModel.getProperty(cur.getObject().asResource(), RDFS.label);
              if (objectLabel != null) {
                objectLabelStr = objectLabel.getString();
              }
            }
            
            // add a new bullet point to the detailed annotation
            details += "<li>" + compLabel + " " + predicateLabelStr + objectLabelStr;
          }
        }
        details += "</ul><a href=\"#privacy-top\">Back to the top</a></div>";
      }
      summary += "</ul></p><a href=\"#privacy-top\">Back to the top</a></div>";
      
      // find all statements containing "ai:connectsTo"
      StmtIterator connectsIter = model.listStatements(null, aiConnectsTo, (RDFNode) null);
      if (connectsIter.hasNext()) {
        interactions += "<p>The privacy-sensitive components interact in the following ways: " +
                       "<ul>";
        while (connectsIter.hasNext()) {
          // find the subject and object of the connectsTo statement, then track down their AI classes
          Statement cur = connectsIter.nextStatement();
          Resource subject = cur.getSubject().asResource();
          Resource object = cur.getObject().asResource();
          
          String subjectLabelStr = subject.getURI();
          String objectLabelStr = object.getURI();
          
          Resource subjectType = model.getProperty(subject, RDF.type).getResource();
          Resource objectType = model.getProperty(object, RDF.type).getResource();
          
          Statement subjectClass = ontModel.getProperty(subjectType, RDFS.subClassOf);
          Statement objectClass = ontModel.getProperty(objectType, RDFS.subClassOf);
          
          // if subjectClass or objectClass is null, this means the specific event, method, or property
          // has not been defined in the privacy template of the component. In this case, we just
          // use the URI of the instance for now
          if (subjectClass != null) {
            // find the label for the subject
            Statement subjectLabel = ontModel.getProperty(subjectType, RDFS.label);
            subjectLabelStr = (subjectLabel==null) ? subject.getURI() : subjectLabel.getString();
          }
          
          if (objectClass != null) {
            // find the label for the object
            Statement objectLabel = ontModel.getProperty(objectType, RDFS.label);
            objectLabelStr = (objectLabel==null) ? object.getURI() : objectLabel.getString();
          }
          
          /* 
           * Classes are either ai:ComponentEvent, ai:ComponentMethod or ai:ComponentProperty
           * Interactions possible:
           *   ai:ComponentEvent ai:connectsTo ai:ComponentMethod
           *   ai:ComponentEvent ai:connectsTo ai:ComponentProperty
           *   ai:ComponentMethod ai:connectsTo ai:ComponentProperty
           *   
           */
          
          if (subjectClass == null || objectClass == null) { // template does not contain the subject or object
            interactions += "<li>" + subjectLabelStr + " connects to " + objectLabelStr;
          } else if (subjectClass.getResource().equals(aiComponentEvent) && objectClass.getResource().equals(aiComponentMethod)) {
            interactions += "<li>when " + subjectLabelStr + ", " + "the " + objectLabelStr + " is called."; 
          } else if (subjectClass.getResource().equals(aiComponentEvent) && objectClass.getResource().equals(aiComponentProperty)) {
            interactions += "<li>when " + subjectLabelStr + ", " + "the " + objectLabelStr + " is accessed.";
          } else if (subjectClass.getResource().equals(aiComponentMethod) && objectClass.getResource().equals(aiComponentProperty)) {
            interactions += "<li>" + subjectLabelStr + " is called with " + objectLabelStr + " as the parameter.";
          } else { // non-traditional interaction
            interactions += "<li>" + subjectLabelStr + " connects to " + objectLabelStr;
          }
        }
        interactions += "</ul></p><a href=\"#privacy-top\">Back to the top</a></div>";
      } else {
        interactions += "<p>There are no interactions between the application's privacy-sensitive components.</p></div>";
      }
    } else {
      // No privacy-sensitive components in this AppInventor project
      summary += "<p>This application does not contain any privacy-sensitive components as defined in AppInventor.</p></div>";
      interactions = "";
    }
    
    html += title + intro + summary + interactions + details + "</body></html>";
    return html;
  }
  
  // Get component list
  private List<String> getComponentList(List<String> projectFiles, String userId, long projectId) {
    List<String> appComponents = new ArrayList<String>();
    for (String filename : projectFiles) {
      // .scm contains list of components
      if (filename.substring(filename.length()-4).equals(".scm")) {
        JSONObject propertiesObject = YoungAndroidSourceAnalyzer.parseSourceFile(storageIo.downloadFile(userId, projectId, filename, StorageUtil.DEFAULT_CHARSET), JSON_PARSER);
        JSONObject formProperties = propertiesObject.get("Properties").asObject();
        Map<String,JSONValue> allProperties = formProperties.getProperties();
        if (allProperties.containsKey("$Components")) {
          JSONArray components = formProperties.get("$Components").asArray();
          getAllComponents(components, appComponents);
        }
      }
    }
    return appComponents;
  }
  
  // Get all components in the project, recursively
  private void getAllComponents(JSONArray components, List<String> appComponents) {
    for (JSONValue component : components.getElements()) {
      String element = component.asObject().get("$Type").asString().getString();
      if (!appComponents.contains(element)) {
        appComponents.add(element);
      }
      
      Map<String,JSONValue> allProperties = component.asObject().getProperties();
      if (allProperties.containsKey("$Components")) {
        JSONArray nestedComponents = component.asObject().get("$Components").asArray();
        getAllComponents(nestedComponents, appComponents);
      }
    }
  }
  
  // Get blocks logic
  private List<String> getBlocks(List<String> projectFiles, String userId, long projectId) {
    List<String> xmlFiles = new ArrayList<String>();
    for (String filename : projectFiles) {
      // .bky contains blocks logic
      if (filename.substring(filename.length()-4).equals(".bky")) {
        xmlFiles.add(storageIo.downloadFile(userId, projectId, filename, StorageUtil.DEFAULT_CHARSET));
      }
    }
    return xmlFiles;
  }
  
  // Process relationships between AppInventor components as parsed by the BkyParserHandler
  private void processRelationships(ArrayList<ArrayList<ArrayList<String>>> relationships) {
    for (ArrayList<ArrayList<String>> relationship : relationships) {
      assert (relationship.size() == 2); // each relationship is between a pair of components only
      ArrayList<String> comp1 = relationship.get(0);
      ArrayList<String> comp2 = relationship.get(1);
      
      // add components to the privacy description
      Resource parentPredInstance = addComponentDetails(comp1);
      Resource childPredInstance = addComponentDetails(comp2);
      
      if (parentPredInstance != null && childPredInstance != null) {
        // add the relationship to the privacy description
        model.add(parentPredInstance, aiConnectsTo, childPredInstance);
      }
    }
  }
  
  // Helper function for processRelationships that adds components to the privacy description and defines their methods, properties or events used
  private Resource addComponentDetails(ArrayList<String> compDetails) {
    String comp_type = compDetails.get(0);
    String comp_name = compDetails.get(1);
    String predicate_type = compDetails.get(2);
    String predicate_name = compDetails.get(3);
    
    if (predicate_type.equalsIgnoreCase("NONE") || predicate_name.equalsIgnoreCase("NONE")) {
      // there is no predicate type or instance indicated for this block, so we return null
      LOG.info("PrivacyEditorService: The component block " + comp_name + "of type " + comp_type + "has no valid predicate type (i.e. no method, property or event)");
      return null;
    }
    
    Resource predicateInstance = model.createResource(project_ns + comp_name + "_" + predicate_name).addProperty(RDF.type, ResourceFactory.createResource(COMPONENT_NS + comp_type + "#" + predicate_name));
    Resource parentInstance = model.createResource(project_ns + comp_name).addProperty(RDF.type, ResourceFactory.createResource(COMPONENT_NS + comp_type + "#" + comp_type + "Component"))
                                                                       .addProperty(ResourceFactory.createProperty(AI_NS, predicate_type), predicateInstance);
    return predicateInstance; 
  }
  
  // Get a list of available templates using given classpath
  private List<String> getTemplates(Class loader) {
    List<String> templates = new ArrayList<String>();
    InputStream in = loader.getResourceAsStream(TEMPLATE_LOC);
    BufferedReader rdr = new BufferedReader(new InputStreamReader(in));
    String line;
    try {
      while ((line = rdr.readLine()) != null) {
          templates.add(line);
      }
      rdr.close();
    }
    catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return templates;
  }
}
