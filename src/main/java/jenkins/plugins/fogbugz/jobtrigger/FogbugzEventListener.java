package jenkins.plugins.fogbugz.jobtrigger;

import hudson.Extension;
import hudson.model.*;
import jenkins.model.Jenkins;
import jenkins.plugins.fogbugz.notifications.FogbugzNotifier;
import lombok.extern.java.Log;
import org.kohsuke.stapler.QueryParameter;
import org.paylogic.fogbugz.FogbugzCase;
import org.paylogic.fogbugz.FogbugzCaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;


/**
 * FogbugzEventListener: a HTTP endpoint that triggers builds.
 * Set the build to trigger in Jenkins' global settings page.
 * If you don't set a build, the trigger will do noting and quit immediately.
 */
@Log
@Extension
public class FogbugzEventListener implements UnprotectedRootAction {

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return "Fogbugz Event Listener";
    }

    public String getUrlName() {
        return "fbTrigger";
    }

    public void doIndex(@QueryParameter(required = true) int caseid) {
        if (caseid == 0) {
            return;
        }
        log.info("Fogbugz URLTrigger received, processing...");

        FogbugzNotifier fbNotifier = new FogbugzNotifier();

        if (fbNotifier.getDescriptor().getJob_to_trigger().isEmpty()) {
            // Skip all of this, user has 'disabled' trigger.
            return;
        }

        FogbugzCaseManager caseManager = fbNotifier.getFogbugzCaseManager();
        FogbugzCase fbCase = caseManager.getCaseById(caseid);

        // Check for correct format of feature branch if regex and field name are set.
        // TODO: make this independant of paylogic specifics
        if (!fbNotifier.getFeatureBranchRegex().isEmpty() && !fbNotifier.getDescriptor().getFeatureBranchFieldname().isEmpty()) {
            String featureBranch = "";
            try {
                featureBranch = fbCase.getFeatureBranch().split("#")[1];
            } catch (Exception e) {
                log.log(Level.SEVERE, "No feature branch found in correct format. Aborting trigger...");
                fbCase = caseManager.assignToGatekeepers(fbCase);
                caseManager.saveCase(fbCase, "This case is not suitable for automatic Gatekeepering/Mergekeepering.\n" +
                        "Please merge the case manually.\n" +
                        "If you are sure this case should be automatically gatekeepered, " +
                        "set the 'feature branch' field correctly, eg: 'maikel/repo#c1337' and try again.");
                return;
            }
        }

        // Check for correct format of release branch if regex and field name are set.
        // TODO: make this independant of paylogic specifics
        if (!fbNotifier.getReleaseBranchRegex().isEmpty() && !fbNotifier.getDescriptor().getTargetBranchFieldname().isEmpty()) {
            // Here, we check if case is paylogic release, else return error message.
            if (!fbCase.getOriginalBranch().matches(fbNotifier.getReleaseBranchRegex())) {
                fbCase = caseManager.assignToGatekeepers(fbCase);
                log.log(Level.SEVERE, "No original branch found in correct format. Aborting trigger...");
                caseManager.saveCase(fbCase, "This case is not suitable for automatic Gatekeepering/Mergekeepering.\n" +
                        "Please merge the case manually.\n" +
                        "If you are sure this case should be automatically gatekeepered, " +
                        "set the 'original branch' field correctly, eg: 'r1350' and try again.");
                return;
            }
        }

        // Search for Job that'll be triggered.
        for (Project<?, ?> p: Jenkins.getInstance().getItems(Project.class)) {
            if (p.getName().equals(new FogbugzNotifier().getDescriptor().getJob_to_trigger())) {

                // Fetch default Parameters
                ParametersDefinitionProperty property = p.getProperty(ParametersDefinitionProperty.class);
                final List<ParameterValue> parameters = new ArrayList<ParameterValue>();
                for (final ParameterDefinition pd : property.getParameterDefinitions()) {
                    final ParameterValue param = pd.getDefaultParameterValue();
                    if (pd.getName().equals("CASE_ID")) {  // Override CASE_ID param if it's there.
                        parameters.add(new StringParameterValue("CASE_ID", Integer.toString(fbCase.getId())));
                    } else if (param != null) {
                        parameters.add(param);
                    }
                }

                // Here, we actually schedule the build.
                p.scheduleBuild2(0, new FogbugzBuildCause(), new ParametersAction(parameters));
            }
        }
    }
}