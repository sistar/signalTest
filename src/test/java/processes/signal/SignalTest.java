package processes.signal;

import org.activiti.cdi.BusinessProcess;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.activiti.engine.impl.persistence.entity.SignalEventSubscriptionEntity;
import org.activiti.engine.runtime.ProcessInstance;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.DependencyResolvers;
import org.jboss.shrinkwrap.resolver.api.maven.EffectivePomMavenDependencyResolver;
import org.jboss.shrinkwrap.resolver.api.maven.MavenDependencyResolver;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(Arquillian.class)
public class SignalTest {
    @Inject
    TaskService taskService;
    @Inject
    private RuntimeService runtimeService;

    @Deployment
    public static Archive<?> deployArchive() {
        EffectivePomMavenDependencyResolver resolver = DependencyResolvers.use(MavenDependencyResolver.class).loadEffectivePom("pom.xml");
        resolver.up();
        return ShrinkWrap.create(WebArchive.class, "signalTest.war")
                .addClass(SignalTest.class)
                .addClass(SignalTest.SendSignalDelegate.class)
                .addClass(SignalTest.SignalReceivedDelegate.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsManifestResource("ARQUILLIAN-MANIFEST-JBOSS7.MF", "MANIFEST.MF")
                .addAsWebResource("META-INF/processes.xml", "WEB-INF/classes/META-INF/processes.xml")
                .addAsResource("processes/signal/SignalEventTests.catchAlertSignalBoundaryWithReceiveTask.bpmn20.xml")
                .addAsResource("processes/signal/SignalEventTests.throwAlertSignalWithDelegate.bpmn20.xml")
                .addAsLibraries(resolver.artifact("com.camunda.fox.platform:fox-platform-client:6.0.0").resolveAsFiles());
    }

    @Named
    public static class SignalReceivedDelegate implements JavaDelegate {

        @Inject
        private BusinessProcess businessProcess;

        public void execute(DelegateExecution execution) {
            businessProcess.setVariable("processName", "catchSignal-visited (was " + businessProcess.getVariable("processName") + ")");
//      log.log(Level.INFO, "");
        }
    }

    @Named
    public static class SendSignalDelegate implements JavaDelegate {

        @Inject
        private RuntimeService runtimeService;

        @Inject
        private BusinessProcess businessProcess;

        public void execute(DelegateExecution execution) throws Exception {
            businessProcess.setVariable("processName", "throwSignal-visited (was " + businessProcess.getVariable("processName") + ")");

            String signalProcessInstanceId = (String) execution.getVariable("signalProcessInstanceId");
            String executionId = runtimeService.createExecutionQuery().processInstanceId(signalProcessInstanceId).signalEventSubscription("alert").singleResult().getId();
            CommandContext commandContext = Context.getCommandContext();
            List<SignalEventSubscriptionEntity> findSignalEventSubscriptionsByEventName = commandContext
                    .getEventSubscriptionManager()
                    .findSignalEventSubscriptionsByNameAndExecution("alert", executionId);
            for (SignalEventSubscriptionEntity signalEventSubscriptionEntity : findSignalEventSubscriptionsByEventName) {
                signalEventSubscriptionEntity.eventReceived(true, false);
            }
            // runtimeService.signalEventReceived("alert", executionId);
        }

    }

    @Test
    public void testSignalCatchBoundaryWithVariables() throws InterruptedException {

        HashMap<String, Object> variables1 = new HashMap<String, Object>();
        variables1.put("processName", "catchSignal");
        ProcessInstance piCatchSignal = runtimeService.startProcessInstanceByKey("catchSignal", variables1);

        HashMap<String, Object> variables2 = new HashMap<String, Object>();
        variables2.put("processName", "throwSignal");
        variables2.put("signalProcessInstanceId", piCatchSignal.getProcessInstanceId());
        ProcessInstance piThrowSignal = runtimeService.startProcessInstanceByKey("throwSignal", variables2);
        Thread.sleep(4000L);
        assertEquals(1, runtimeService.createExecutionQuery().processInstanceId(piCatchSignal.getProcessInstanceId()).activityId("receiveTask").count());
        assertEquals(1, runtimeService.createExecutionQuery().processInstanceId(piThrowSignal.getProcessInstanceId()).activityId("receiveTask").count());

        assertEquals("catchSignal-visited (was catchSignal)", runtimeService.getVariable(piCatchSignal.getId(), "processName"));
        assertEquals("throwSignal-visited (was throwSignal)", runtimeService.getVariable(piThrowSignal.getId(), "processName"));

        // clean up
        runtimeService.signal(piCatchSignal.getId());
        runtimeService.signal(piThrowSignal.getId());
    }
}
