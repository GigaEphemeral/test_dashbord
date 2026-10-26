package com.javalearn.lab.chaos.traffic;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts and stops the interactive-demo background traffic container.
 *
 * <p>The container itself is defined by compose (profile {@code traffic}) so the
 * load shape stays in one place with the other k6 scenarios; the controller only
 * starts and stops it through {@link ContainerGateway}, which still enforces the
 * {@code lab=java-learn} whitelist. Deliberately <em>not</em> a chaos action:
 * traffic has no TTL to revert, and guide pause must neither freeze it nor keep
 * generating load behind the operator's back.</p>
 */
@Service
public class TrafficService {

    private static final Logger log = LoggerFactory.getLogger(TrafficService.class);

    private final ContainerGateway containerGateway;
    private final ChaosProperties properties;

    public TrafficService(ContainerGateway containerGateway, ChaosProperties properties) {
        this.containerGateway = containerGateway;
        this.properties = properties;
    }

    public TrafficStateView state() {
        String container = containerName();
        try {
            return view(containerGateway.inspectLabContainer(container));
        } catch (ContainerNotFoundException e) {
            // The traffic profile is optional at up time; the console shows this
            // as "not installed" instead of failing its status poll.
            return TrafficStateView.missing(container);
        }
    }

    public TrafficStateView start() {
        String container = containerName();
        try {
            containerGateway.start(container);
        } catch (ContainerNotFoundException e) {
            throw new ContainerNotFoundException(
                    "Background traffic container '" + container + "' is not created; "
                            + "start the stack with up.sh, which creates it from the traffic profile");
        }
        log.info("Background traffic started: {}", container);
        return state();
    }

    public TrafficStateView stop() {
        String container = containerName();
        try {
            containerGateway.stop(container, properties.getTraffic().getStopGraceSeconds());
        } catch (ContainerNotFoundException e) {
            throw new ContainerNotFoundException(
                    "Background traffic container '" + container + "' is not created; "
                            + "start the stack with up.sh, which creates it from the traffic profile");
        }
        log.info("Background traffic stopped: {}", container);
        return state();
    }

    private String containerName() {
        return properties.getTraffic().getContainerName();
    }

    private static TrafficStateView view(ContainerView container) {
        return new TrafficStateView(container.name(), container.state(), container.health(),
                "running".equals(container.state()));
    }
}
