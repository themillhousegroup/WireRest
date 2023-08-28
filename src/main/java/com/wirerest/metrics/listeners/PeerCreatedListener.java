package com.wirerest.metrics.listeners;

import com.wirerest.metrics.MetricsService;
import com.wirerest.wireguard.events.PeerCreatedEvent;
import com.wirerest.wireguard.peer.WgPeer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class PeerCreatedListener implements ApplicationListener<PeerCreatedEvent> {
    private final MetricsService metricsService;

    @Autowired
    public PeerCreatedListener(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Override
    public void onApplicationEvent(PeerCreatedEvent event) {
        WgPeer createdPeer = event.getPeer();
        metricsService.metrics.totalPeers.increment();
        metricsService.metrics.freeV4Ips.subtract(
                createdPeer.getAllowedSubnets().getIPv4Subnets().size()
        );
    }
}
