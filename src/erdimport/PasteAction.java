package erdimport;

import com.vp.plugin.action.VPAction;
import com.vp.plugin.action.VPActionController;

/** "Paste PlantUML ERD" button. */
public class PasteAction implements VPActionController {
  public void performAction(VPAction action) {
    R.loader = getClass().getClassLoader();
    Runner.fromPaste();
  }

  public void update(VPAction action) {}
}
