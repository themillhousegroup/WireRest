package com.wireguard.api.peer;

import com.wireguard.api.ResourceNotFoundException;
import com.wireguard.api.converters.WgPeerDTOFromWgPeerConverter;
import com.wireguard.api.dto.PageDTO;
import com.wireguard.external.network.NoFreeIpException;
import com.wireguard.external.network.Subnet;
import com.wireguard.external.network.SubnetV6;
import com.wireguard.external.wireguard.IpAllocationRequestOneIfNullSubnets;
import com.wireguard.external.wireguard.Paging;
import com.wireguard.external.wireguard.ParsingException;
import com.wireguard.external.wireguard.PeerCreationRequest;
import com.wireguard.external.wireguard.peer.CreatedPeer;
import com.wireguard.external.wireguard.peer.WgPeer;
import com.wireguard.external.wireguard.peer.WgPeerService;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.hamcrest.Matchers.containsString;

@WebFluxTest(PeerController.class)
class PeerControllerTest {

    @Autowired
    private WebTestClient webClient;
    WgPeerDTOFromWgPeerConverter peerDTOC = new WgPeerDTOFromWgPeerConverter();
    private final static String BASE_URL = "/v1/peers";

    @MockBean
    WgPeerService wgPeerService;
    List<WgPeer> peerList = List.of(
            WgPeer.publicKey(getFakePubKey()).build(),
            WgPeer.publicKey("PubKey2")
                    .presharedKey("PresharedKey2")
                    .allowedIPv4Subnets(Set.of(Subnet.valueOf("10.0.0.1/32"), Subnet.valueOf("10.1.1.1/30")))
                    .allowedIPv6Subnets(Set.of(SubnetV6.valueOf("2001:db8::/32")))
                    .transferTx(100)
                    .transferRx(200)
                    .latestHandshake(300)
                    .endpoint("1.1.1.1")
                    .build()
    );
    Paging<WgPeer> paging = new Paging<>(WgPeer.class);


    @Test
    void getPeers() {
        Page<WgPeer> expected = paging.apply(Pageable.ofSize(2), peerList);
        Mockito.when(wgPeerService.getPeers(Mockito.any())).thenReturn(expected);

        Iterator<WgPeer> peersIter = peerList.iterator();
        webClient.get().uri(BASE_URL).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].publicKey").isEqualTo(peersIter.next().getPublicKey())
                .jsonPath("$.content[1].presharedKey").isEqualTo(peersIter.next().getPresharedKey());
    }

    @Test
    void getPeersWithPageable() {
        List<WgPeer> peers = peerList.stream().toList().subList(0, 2);
        Mockito.when(wgPeerService.getPeers(PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "PresharedKey"))))
                .thenReturn(paging.apply(Pageable.ofSize(20), peerList));
        PageDTO<WgPeerDTO> expected = new PageDTO<>(
                1,
                2,
                peers.stream().map(peerDTOC::convert).toList()
        );
        webClient.get().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("page", 1)
                        .queryParam("limit", 2)
                        .queryParam("sort", "PresharedKey.asc")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(PageDTO.class);
    }

    @Test
    void getPeersWithWrongPage() {
        Mockito.when(wgPeerService.getPeers(PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "WrongField"))))
                .thenThrow(new ParsingException("WrongField", new RuntimeException()));
        webClient.get().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("page", -1)
                        .queryParam("limit", 2)
                        .queryParam("sort", "PresharedKey.asc")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.message").value(containsString("page"));
    }

    @Test
    void getPeersWithWrongLimit() {
        webClient.get().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("page", 0)
                        .queryParam("limit", -1)
                        .queryParam("sort", "PresharedKey.asc")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.message").value(containsString("limit"));
    }

    @Test
    void getPeersWithWrongSortKey() {
        Mockito.when(wgPeerService.getPeers(PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "WrongKey"))))
                .thenThrow(new ParsingException("WrongField", new RuntimeException()));
        webClient.get().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("page", 0)
                        .queryParam("limit", 1)
                        .queryParam("sort", "WrongKey.asc")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.message").value(containsString("Field"));
    }


    @Test
    void getPeerByPublicKey() {
        Optional<WgPeer> peer = Optional.of(
                peerList.stream().filter(p -> p.getPublicKey().equals(getFakePubKey()))
                        .findFirst().get()
        );
        Mockito.when(wgPeerService.getPeerByPublicKeyOrThrow(getFakePubKey()))
                .thenReturn(peer.get());
        webClient.get().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL+"/find")
                        .queryParam("publicKey", getFakePubKey())
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WgPeerDTO.class).isEqualTo(peerDTOC.convert(peer.get()));
    }

    @Test
    void getPeerByPublicKeyNotFound() {
        Mockito.when(wgPeerService.getPeerByPublicKeyOrThrow(getFakePubKey()))
                .thenThrow(new ResourceNotFoundException("not found"));
        webClient.get().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL+"/find")
                        .queryParam("publicKey", getFakePubKey())
                        .build())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.message").value(containsString("not found"));
    }


    @Test
    void createPeer() {
        CreatedPeer newPeer = new CreatedPeer(
                "PubKey3",
                "PresharedKey3",
                "PrivateKey3",
                Set.of(Subnet.valueOf("10.0.0.0/32")),
                0
        );
        Mockito.when(wgPeerService.createPeerGenerateNulls(Mockito.any())).thenReturn(newPeer);
        webClient.post().uri(BASE_URL).exchange()
                .expectStatus().isCreated()
                .expectBody(CreatedPeerDTO.class)
                .isEqualTo(CreatedPeerDTO.from(newPeer));
    }

    @Test
    void createPeerWithParams() {
        CreatedPeer newPeer = new CreatedPeer(
                getFakePubKey(),
                getFakePubKey(),
                getFakePubKey(),
                Set.of(Subnet.valueOf("10.0.0.10/32")),
                25);
        Mockito.when(wgPeerService.createPeerGenerateNulls(new PeerCreationRequest(newPeer.getPublicKey(),
                newPeer.getPresharedKey(),
                newPeer.getPrivateKey(),
                new IpAllocationRequestOneIfNullSubnets(newPeer.getAllowedSubnets()),
                25))).thenReturn(newPeer);
        webClient.post().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("publicKey", newPeer.getPublicKey())
                        .queryParam("presharedKey", newPeer.getPresharedKey())
                        .queryParam("privateKey", newPeer.getPrivateKey())
                        .queryParam("allowedIps", newPeer.getAllowedSubnets())
                        .queryParam("persistentKeepalive", 25)
                        .build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreatedPeerDTO.class)
                .isEqualTo(CreatedPeerDTO.from(newPeer));
    }

    @Test
    void createPeerWhenNoFreeIps() {
        Mockito.when(wgPeerService.createPeerGenerateNulls(Mockito.any())).thenThrow(new NoFreeIpException("No free ip"));
        webClient.post().uri(BASE_URL).exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo(500)
                .jsonPath("$.message").value(containsString("ip"));
    }

    @Test
    void deletePeer() {
        WgPeer peerToDelete = peerList.get(1);
        Mockito.when(wgPeerService.deletePeer(Mockito.anyString())).thenReturn(peerToDelete);
        webClient.delete().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("publicKey", getFakePubKey())
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WgPeerDTO.class)
                .isEqualTo(peerDTOC.convert(peerToDelete));
    }


    @Test
    void deletePeerNotExists() {
        Mockito.when(wgPeerService.deletePeer(Mockito.anyString())).thenThrow(new NoSuchElementException("not found"));
        webClient.delete().uri(uriBuilder -> uriBuilder
                        .path(BASE_URL)
                        .queryParam("publicKey", getFakePubKey())

                        .build())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.message").value(containsString("not found"));

    }

    private String getFakePubKey() {
        return "CkwTo0AKBXMyX9Mqf0SRrq31hZAb6s5C7k1UU94m024=";
    }

    @SneakyThrows
    private String urlEncodeValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
    }


}