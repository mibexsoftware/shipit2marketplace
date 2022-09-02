import com.atlassian.bamboo.specs.api.BambooSpec;
import com.atlassian.bamboo.specs.api.builders.BambooKey;
import com.atlassian.bamboo.specs.api.builders.AtlassianModule;
import com.atlassian.bamboo.specs.api.builders.BambooOid;
import com.atlassian.bamboo.specs.api.builders.deployment.Deployment;
import com.atlassian.bamboo.specs.api.builders.deployment.Environment;
import com.atlassian.bamboo.specs.api.builders.deployment.ReleaseNaming;
import com.atlassian.bamboo.specs.api.builders.permission.DeploymentPermissions;
import com.atlassian.bamboo.specs.api.builders.permission.EnvironmentPermissions;
import com.atlassian.bamboo.specs.api.builders.permission.PermissionType;
import com.atlassian.bamboo.specs.api.builders.permission.Permissions;
import com.atlassian.bamboo.specs.api.builders.plan.PlanIdentifier;
import com.atlassian.bamboo.specs.api.builders.task.AnyTask;
import com.atlassian.bamboo.specs.builders.task.ArtifactDownloaderTask;
import com.atlassian.bamboo.specs.builders.task.CleanWorkingDirectoryTask;
import com.atlassian.bamboo.specs.builders.task.DownloadItem;
import com.atlassian.bamboo.specs.util.BambooServer;
import com.atlassian.bamboo.specs.util.MapBuilder;
import com.atlassian.bamboo.specs.api.BambooSpec;
import com.atlassian.bamboo.specs.api.builders.permission.PlanPermissions;
import com.atlassian.bamboo.specs.api.builders.plan.Job;
import com.atlassian.bamboo.specs.api.builders.plan.Plan;
import com.atlassian.bamboo.specs.api.builders.plan.PlanIdentifier;
import com.atlassian.bamboo.specs.api.builders.plan.Stage;
import com.atlassian.bamboo.specs.api.builders.plan.artifact.Artifact;
import com.atlassian.bamboo.specs.api.builders.plan.branches.BranchCleanup;
import com.atlassian.bamboo.specs.api.builders.plan.branches.PlanBranchManagement;
import com.atlassian.bamboo.specs.api.builders.plan.configuration.ConcurrentBuilds;
import com.atlassian.bamboo.specs.api.builders.project.Project;
import com.atlassian.bamboo.specs.builders.task.ScriptTask;

@BambooSpec
public class ShipItSpec {

    public static Plan plan() {
        final Plan plan = new Plan(new Project()
                .oid(new BambooOid("rnl8i5pnngu9"))
                .key(new BambooKey("MYAP"))
                .name("MyApp"),
                "BuildApp",
                new BambooKey("BUIL"))
                .oid(new BambooOid("rnbjakcftog1"))
                .description("Builds app we want to publish to marketplace")
                .pluginConfigurations(new ConcurrentBuilds())
                .stages(new Stage("Default Stage")
                        .jobs(new Job("Default Job",
                                new BambooKey("JOB1"))
                                .artifacts(new Artifact()
                                        .name("MyApp")
                                        .copyPatterns("**.jar")
                                        .shared(true)
                                        .required(true))
                                .tasks(new ScriptTask()
                                        .description("Build app artifact")
                                        .inlineBody("touch my-app.jar"))))
                .planBranchManagement(new PlanBranchManagement()
                        .delete(new BranchCleanup())
                        .notificationForCommitters());
        return plan;
    }

    public static PlanPermissions planPermission() {
        final PlanPermissions planPermission = new PlanPermissions(new PlanIdentifier("MYAP", "BUIL"))
                .permissions(new Permissions()
                        .userPermissions("admin", PermissionType.EDIT, PermissionType.VIEW_CONFIGURATION, PermissionType.VIEW, PermissionType.ADMIN, PermissionType.CLONE, PermissionType.BUILD)
                        .loggedInUserPermissions(PermissionType.VIEW)
                        .anonymousUserPermissionView());
        return planPermission;
    }

    public static Deployment deployment() {
        final Deployment rootObject = new Deployment(new PlanIdentifier("MYAP", "BUIL")
                .oid(new BambooOid("rnbjakcftog1")),
                "DeployMyApp")
                .oid(new BambooOid("rno0a1j5beo1"))
                .releaseNaming(new ReleaseNaming("release-1")
                        .autoIncrement(true))
                .environments(new Environment("Marketplace")
                        .tasks(new CleanWorkingDirectoryTask(),
                                new ArtifactDownloaderTask()
                                        .description("Download release contents")
                                        .artifacts(new DownloadItem()
                                                .artifact("MyApp")),
                                new AnyTask(new AtlassianModule("ch.mibex.bamboo.shipit2mpac:shipit2marketplace.task"))
                                        .description("Ship my App")
                                        .configuration(new MapBuilder()
                                                .put("publicVersion", "true")
                                                .put("runOnBranchBuilds", "false")
                                                .put("serverDeployment", "true")
                                                .put("jql", "status in (resolved,closed,done)")
                                                .put("artifactToDeployKey", "3440641:MyApp:2:0")
                                                .put("jiraProjectKey", "")
                                                .put("bambooUserId", "admin")
                                                .put("createDcDeployment", "false")
                                                .put("deduceBuildNrFromPluginVersion", "true")
                                                .put("jiraReleasePanelDeploymentOnly", "true")
                                                .put("jiraVersionPrefix", "")
                                                .build())));
        return rootObject;
    }

    public static DeploymentPermissions deploymentPermission() {
        final DeploymentPermissions deploymentPermission = new DeploymentPermissions("DeployMyApp")
                .permissions(new Permissions()
                        .userPermissions("admin", PermissionType.EDIT, PermissionType.VIEW_CONFIGURATION, PermissionType.VIEW));
        return deploymentPermission;
    }

    public static EnvironmentPermissions environmentPermission() {
        final EnvironmentPermissions environmentPermission1 = new EnvironmentPermissions("DeployMyApp")
                .environmentName("Marketplace")
                .permissions(new Permissions()
                        .userPermissions("admin", PermissionType.EDIT, PermissionType.VIEW_CONFIGURATION, PermissionType.VIEW, PermissionType.BUILD));
        return environmentPermission1;
    }

    public static void main(String... argv) {
        //By default credentials are read from the '.credentials' file.
        BambooServer bambooServer = new BambooServer("http://localhost:6990/bamboo");

        // Build plan
        bambooServer.publish(ShipItSpec.plan());
        bambooServer.publish(ShipItSpec.planPermission());


        // Deployment
        bambooServer.publish(ShipItSpec.deployment());
        bambooServer.publish(ShipItSpec.deploymentPermission());
        bambooServer.publish(ShipItSpec.environmentPermission());
    }
}