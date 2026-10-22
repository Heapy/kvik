import java.io.File;
import org.apache.maven.model.building.*;
public class ModelOracle {
 public static void main(String[] args) throws Exception {
  var request = new DefaultModelBuildingRequest().setPomFile(new File(args[0])).setProcessPlugins(false).setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
  var model = new DefaultModelBuilderFactory().newInstance().build(request).getEffectiveModel();
  System.out.println("packaging="+model.getPackaging());
  for (var d : model.getDependencies()) System.out.println(d.getGroupId()+":"+d.getArtifactId()+":"+d.getVersion()+" scope="+d.getScope()+" optional="+d.isOptional());
 }
}
