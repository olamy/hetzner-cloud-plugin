/*
 *     Copyright 2021 https://dnation.cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.hetznerclient.AbstractSearchResponse;
import cloud.dnation.hetznerclient.ClientFactory;
import cloud.dnation.hetznerclient.CreateServerRequest;
import cloud.dnation.hetznerclient.CreateServerResponse;
import cloud.dnation.hetznerclient.CreateSshKeyRequest;
import cloud.dnation.hetznerclient.CreateSshKeyResponse;
import cloud.dnation.hetznerclient.GetImagesBySelectorResponse;
import cloud.dnation.hetznerclient.GetNetworksBySelectorResponse;
import cloud.dnation.hetznerclient.GetPlacementGroupsResponse;
import cloud.dnation.hetznerclient.GetServerByIdResponse;
import cloud.dnation.hetznerclient.GetServersBySelectorResponse;
import cloud.dnation.hetznerclient.GetSshKeysBySelectorResponse;
import cloud.dnation.hetznerclient.HetznerApi;
import cloud.dnation.hetznerclient.IdentifiableResource;
import cloud.dnation.hetznerclient.Meta;
import cloud.dnation.hetznerclient.PublicNetRequest;
import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.hetznerclient.SshKeyDetail;
import cloud.dnation.jenkins.plugins.hetzner.connect.ConnectivityType;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import hudson.util.Secret;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cloud.dnation.jenkins.plugins.hetzner.Helper.assertSshKey;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.assertValidResponse;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.getPayload;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.getSSHPublicKeyFromPrivate;
import static cloud.dnation.jenkins.plugins.hetzner.HetznerConstants.LABEL_VALUE_PLUGIN;

@RequiredArgsConstructor
@Slf4j
public class HetznerCloudResourceManager {
    @NonNull
    private final String credentialsId;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static HetznerCloudResourceManager create(String credentialsId) {
        return new HetznerCloudResourceManager(credentialsId);
    }

    private static Map<String, String> createLabelsForServer(String cloudName) {
        final LinkedHashMap<String, String> ret = new LinkedHashMap<>();
        ret.put(HetznerConstants.LABEL_MANAGED_BY, LABEL_VALUE_PLUGIN);
        ret.put(HetznerConstants.LABEL_CLOUD_NAME, cloudName);
        return ret;
    }

    private static Map<String, String> createLabelsForSshKey(String credentialsId) {
        return ImmutableMap.<String, String>builder()
                .put(HetznerConstants.LABEL_MANAGED_BY, LABEL_VALUE_PLUGIN)
                .put(HetznerConstants.LABEL_CREDENTIALS_ID, credentialsId)
                .build();
    }

    /**
     * Build label expression to query SSH key for server in particular cloud.
     *
     * @param credentialsId ID of credentials for SSH access to server
     * @return label expression
     */
    @VisibleForTesting
    static String buildLabelExpressionForSshKey(String credentialsId) {
        return createLabelsForSshKey(credentialsId)
                .entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private HetznerApi proxy() {
        return ClientFactory.create(JenkinsSecretTokenProvider.forCredentialsId(credentialsId));
    }

    /**
     * Attempt to obtain image ID based on label expression.
     * It's expected that provided label expression resolves to single image.
     *
     * @param labelExpression label expression used to filter image
     * @return image ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single image
     */
    private long getImageIdForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getImagesBySelector,
                GetImagesBySelectorResponse::getImages);
    }

    /**
     * Attempt to obtain network ID based on label expression.
     * It's expected that provided label expression resolves to single network.
     *
     * @param labelExpression label expression used to filter image
     * @return network ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single network
     */
    private long getNetworkIdForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getNetworkBySelector,
                GetNetworksBySelectorResponse::getNetworks);
    }

    /**
     * Attempt to obtain placement group based on label expression.
     * It's expected that provided label expression resolves to single placement group.
     *
     * @param labelExpression label expression used to filter placement group
     * @return placement group ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single placement group
     */
    private long getPlacementGroupForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getPlacementGroups,
                GetPlacementGroupsResponse::getPlacementGroups);
    }

    private <R extends AbstractSearchResponse, I extends IdentifiableResource> long searchResourceByLabelExpression(
            String labelExpression,
            Function<String, Call<R>> searchFunction,
            Function<R, List<I>> getItemsFunction) throws IOException {
        log.info("Trying to find single resource for label expression '{}'", labelExpression);
        final Response<R> response = searchFunction.apply(labelExpression).execute();
        assertValidResponse(response);
        List<I> items = getPayload(response, getItemsFunction);
        Preconditions.checkArgument(items.size() == 1,
                "No exact match found for expression '%s', results %d",
                labelExpression, items.size());
        return Iterables.getOnlyElement(items).getId();
    }

    /**
     * Destroy server.
     *
     * @param server server instance to remove from cloud
     */
    public void destroyServer(ServerDetail server) {
        final long serverId = server.getId();
        final HetznerApi client = proxy();
        try {
            assertValidResponse(client.powerOffServer(serverId).execute());
            assertValidResponse(client.deleteServer(serverId).execute());
        } catch (IOException e) {
            log.error("Unable to destroy server with ID = {}", serverId, e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Get or create SSH key in cloud for given credentialsId derived from server template.
     *
     * @param template instance of {@link HetznerServerTemplate} to get credentialsId from
     * @return SshKeyDetail
     */
    private SshKeyDetail getOrCreateSshKey(HetznerServerTemplate template) throws IOException {
        final HetznerApi client = proxy();
        final String credentialsId = template.getConnector().getSshCredentialsId();

        final BasicSSHUserPrivateKey privateKey = assertSshKey(credentialsId);
        final Response<GetSshKeysBySelectorResponse> searchResponse = client.getSshKeysBySelector(
                        buildLabelExpressionForSshKey(credentialsId))
                .execute();
        assertValidResponse(searchResponse);
        final List<SshKeyDetail> sshKeys = getPayload(searchResponse, GetSshKeysBySelectorResponse::getSshKeys);
        if (!sshKeys.isEmpty()) {
            return Iterables.getOnlyElement(sshKeys);
        }
        final String publicKey = getSSHPublicKeyFromPrivate(privateKey.getPrivateKey(),
                Secret.toString(privateKey.getPassphrase()));
        final Response<CreateSshKeyResponse> createResponse = proxy().createSshKey(new CreateSshKeyRequest()
                        .labels(createLabelsForSshKey(credentialsId))
                        .name(template.getConnector().getSshCredentialsId())
                        .publicKey(publicKey))
                .execute();
        return assertValidResponse(createResponse, CreateSshKeyResponse::getSshKey);
    }

    /**
     * Update {@link HetznerServerInfo} with fresh data from cloud.
     *
     * @param info server info
     * @return ServerDetail
     * @throws IllegalArgumentException if server didn't respond with code HTTP200
     * @throws IllegalStateException    if API call fails
     */
    public HetznerServerInfo refreshServerInfo(HetznerServerInfo info) {
        try {
            final Response<GetServerByIdResponse> response = proxy().getServer(info.getServerDetail().getId())
                    .execute();
            info.setServerDetail(assertValidResponse(response, GetServerByIdResponse::getServer));
            return info;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Create new server instance.
     *
     * @param agent agent instance
     * @return instance of {@link CreateServerResponse}
     */
    public HetznerServerInfo createServer(HetznerServerAgent agent) {
        try {
            lock.writeLock().lock();
            final SshKeyDetail sshKey = getOrCreateSshKey(agent.getTemplate());
            final String imageId;
            //check if image is label expression
            if (agent.getTemplate().getImage().contains("=")) {
                //if so, query image ID
                imageId = String.valueOf(getImageIdForLabelExpression(agent.getTemplate().getImage()));
            } else {
                imageId = agent.getTemplate().getImage();
            }

            final CreateServerRequest createServerRequest = new CreateServerRequest();
            if (agent.getTemplate().isAutomountVolumes()) {
                createServerRequest.setAutomount(true);
            }
            if (!Strings.isNullOrEmpty(agent.getTemplate().getVolumeIds())) {
                createServerRequest.setVolumes(Helper.idList(agent.getTemplate().getVolumeIds()));
            }
            final ConnectivityType ct = agent.getTemplate().getConnectivity().getType();
            if (ct == ConnectivityType.BOTH || ct == ConnectivityType.PRIVATE) {
                if (!Strings.isNullOrEmpty(agent.getTemplate().getNetwork())) {
                    final long networkId;
                    if (Helper.isPossiblyLong(agent.getTemplate().getNetwork())) {
                        networkId = Long.parseLong(agent.getTemplate().getNetwork());
                    } else {
                        networkId = getNetworkIdForLabelExpression(agent.getTemplate().getNetwork());
                    }
                    if (ct == ConnectivityType.PRIVATE) {
                        final PublicNetRequest pn = new PublicNetRequest();
                        pn.setEnableIpv4(false);
                        pn.setEnableIpv6(false);
                        createServerRequest.setPublicNet(pn);
                    }
                    createServerRequest.setNetworks(Lists.newArrayList(networkId));
                }
            }
            final String placementGroup = agent.getTemplate().getPlacementGroup();
            if (!Strings.isNullOrEmpty(placementGroup)) {
                if(Helper.isPossiblyLong(placementGroup)) {
                    createServerRequest.setPlacementGroup(Long.parseLong(placementGroup));
                } else {
                    createServerRequest.setPlacementGroup(getPlacementGroupForLabelExpression(placementGroup));
                }
            }
            if (!Strings.isNullOrEmpty(agent.getTemplate().getUserData())) {
                createServerRequest.setUserData(agent.getTemplate().getUserData());
            }
            createServerRequest.setServerType(agent.getTemplate().getServerType());
            createServerRequest.setImage(imageId);
            createServerRequest.setName(agent.getNodeName());
            createServerRequest.setSshKeys(Collections.singletonList(sshKey.getName()));
            if (agent.getTemplate().getLocation().contains("-")) {
                createServerRequest.setDatacenter(agent.getTemplate().getLocation());
            } else {
                createServerRequest.setLocation(agent.getTemplate().getLocation());
            }
            createServerRequest.setLabels(createLabelsForServer(agent.getTemplate().getCloud().name));
            if (ct == ConnectivityType.BOTH || ct == ConnectivityType.PUBLIC) {
                Optional.of(agent.getTemplate().getPrimaryIp()).ifPresent(ip -> ip.apply(proxy(), createServerRequest));
            }
            final Response<CreateServerResponse> createServerResponse = proxy().createServer(createServerRequest)
                    .execute();
            final HetznerServerInfo info = new HetznerServerInfo(sshKey);
            info.setServerDetail(assertValidResponse(createServerResponse, CreateServerResponse::getServer));
            return info;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ServerDetail> fetchAllServers(String cloudName) throws IOException {
        final List<ServerDetail> result = new ArrayList<>();
        final HetznerApi client = proxy();
        final String selector = createLabelsForServer(cloudName).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        for (int page = 1; ; page++) {
            final Response<GetServersBySelectorResponse> pagedResult = client.getServersBySelector(selector,
                            page, 50)
                    .execute();
            assertValidResponse(pagedResult);
            final Meta meta = pagedResult.body().getMeta();
            final List<ServerDetail> currentPage = pagedResult.body().getServers();
            result.addAll(currentPage);
            if (meta.getPagination().getNextPage() == null) {
                break;
            }
        }
        return result;
    }
}
