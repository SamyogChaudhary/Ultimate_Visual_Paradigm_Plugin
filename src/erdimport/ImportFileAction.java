package erdimport;

import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPActionController;

/** "Import ERD from PlantUML file" button. */
public class ImportFileAction implements VPActionController {
  public void performAction(VPAction action) {
    R.loader = getClass().getClassLoader();
    Runner.fromFiles();
  }

  public void update(VPAction action) {}
}
