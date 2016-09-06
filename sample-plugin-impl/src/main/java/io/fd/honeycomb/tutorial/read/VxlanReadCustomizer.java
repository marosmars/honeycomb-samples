/*
 * Copyright (c) 2016 Cisco and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fd.honeycomb.tutorial.read;

import com.google.common.base.Preconditions;
import io.fd.honeycomb.translate.read.ReadContext;
import io.fd.honeycomb.translate.read.ReadFailedException;
import io.fd.honeycomb.translate.spi.read.ListReaderCustomizer;
import io.fd.honeycomb.translate.v3po.util.NamingContext;
import io.fd.honeycomb.translate.v3po.util.TranslateUtils;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.sample.plugin.rev160918.sample.plugin.params.VxlansBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.sample.plugin.rev160918.sample.plugin.params.vxlans.VxlanTunnel;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.sample.plugin.rev160918.sample.plugin.params.vxlans.VxlanTunnelBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.sample.plugin.rev160918.sample.plugin.params.vxlans.VxlanTunnelKey;
import org.opendaylight.yangtools.concepts.Builder;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.openvpp.jvpp.VppBaseCallException;
import org.openvpp.jvpp.core.dto.VxlanTunnelDetails;
import org.openvpp.jvpp.core.dto.VxlanTunnelDetailsReplyDump;
import org.openvpp.jvpp.core.dto.VxlanTunnelDump;
import org.openvpp.jvpp.core.future.FutureJVppCore;

/**
 * Reader for {@link VxlanTunnel} list node from our YANG model.
 */
public final class VxlanReadCustomizer implements
        ListReaderCustomizer<VxlanTunnel, VxlanTunnelKey, VxlanTunnelBuilder> {

    // JVpp core. This is the Java API for VPP's core API.
    private final FutureJVppCore jVppCore;
    // Naming context for interfaces
    // Honeycomb provides a "context" storage for plugins. This storage is used for storing metadata required during
    // data translation (just like in this plugin). An example of such metadata would be interface identifier. In Honeycomb
    // we use string names for interfaces, however VPP uses only indices (that are created automatically).
    // This means that translation layer has to store the mapping between HC interface name <-> VPP' interface index.
    // And since vxlan tunnel is a type of interface in VPP, the same applies here
    //
    // Honeycomb provides a couple utilities on top of context storage such as NamingContext. It is just a map
    // backed by context storage that makes the lookup and storing easier.
    private final NamingContext vxlanNamingContext;

    public VxlanReadCustomizer(final FutureJVppCore jVppCore, final NamingContext vxlanNamingContext) {
        this.jVppCore = jVppCore;
        this.vxlanNamingContext = vxlanNamingContext;
    }

    /**
     * Provide a list of IDs for all VXLANs in VPP
     */
    @Nonnull
    @Override
    public List<VxlanTunnelKey> getAllIds(@Nonnull final InstanceIdentifier<VxlanTunnel> id,
                                          @Nonnull final ReadContext context)
            throws ReadFailedException {
        // Create Dump request
        final VxlanTunnelDump vxlanTunnelDump = new VxlanTunnelDump();
        // Set Dump request attributes
        // Set interface index to 0, so all interfaces are dumped and we can get the list of all IDs
        vxlanTunnelDump.swIfIndex = 0;
        final VxlanTunnelDetailsReplyDump reply;
        try {
            reply = TranslateUtils.getReplyForRead(jVppCore.vxlanTunnelDump(vxlanTunnelDump).toCompletableFuture(), id);
        } catch (VppBaseCallException e) {
            throw new ReadFailedException(id, e);
        }

        // Check for empty response (no vxlan tunnels to read)
        if (reply == null || reply.vxlanTunnelDetails == null) {
            return Collections.emptyList();
        }

        return reply.vxlanTunnelDetails.stream()
                // Need a name of an interface here. Use context to look it up from index
                // In case the naming context does not contain such mapping, it creates an artificial one
                .map(a -> new VxlanTunnelKey(vxlanNamingContext.getName(a.swIfIndex, context.getMappingContext())))
                .collect(Collectors.toList());
    }

    @Override
    public void merge(@Nonnull final Builder<? extends DataObject> builder, @Nonnull final List<VxlanTunnel> readData) {
        // Just set the readValue into parent builder
        // The cast has to be performed here
        ((VxlansBuilder) builder).setVxlanTunnel(readData);
    }

    @Nonnull
    @Override
    public VxlanTunnelBuilder getBuilder(@Nonnull final InstanceIdentifier<VxlanTunnel> id) {
        // Setting key from id is not necessary, builder will take care of that
        return new VxlanTunnelBuilder();
    }

    /**
     * Read all the attributes of a single VXLAN tunnel
     */
    @Override
    public void readCurrentAttributes(@Nonnull final InstanceIdentifier<VxlanTunnel> id,
                                      @Nonnull final VxlanTunnelBuilder builder,
                                      @Nonnull final ReadContext ctx) throws ReadFailedException {
        // The ID received here contains the name of a particular interface that should be read
        // It was either requested directly by HC users or is one of the IDs from getAllIds that could have been invoked
        // just before this method invocation

        // Create Dump request
        final VxlanTunnelDump vxlanTunnelDump = new VxlanTunnelDump();
        // Set Dump request attributes
        // Set the vxlan index from naming context
        // Naming context must contain the mapping because:
        // 1. The vxlan tunnel was created in VPP using HC + this plugin meaning we stored the mapping in write customizer
        // 2. The vxlan tunnel was already present in VPP, but HC reconciliation mechanism took care of that (as long as proper Initializer is provided by this plugin)
        final String vxlanName = id.firstKeyOf(VxlanTunnel.class).getId();
        vxlanTunnelDump.swIfIndex = vxlanNamingContext.getIndex(vxlanName, ctx.getMappingContext());

        final VxlanTunnelDetailsReplyDump reply;
        try {
            reply = TranslateUtils.getReplyForRead(jVppCore.vxlanTunnelDump(vxlanTunnelDump).toCompletableFuture(), id);
        } catch (VppBaseCallException e) {
            throw new ReadFailedException(id, e);
        }

        Preconditions.checkState(reply != null && reply.vxlanTunnelDetails != null);
        final VxlanTunnelDetails singleVxlanDetail = reply.vxlanTunnelDetails.stream().findFirst().get();

        // Now translate all attributes into provided builder
        final Boolean isIpv6 = TranslateUtils.byteToBoolean(singleVxlanDetail.isIpv6);
        builder.setSrc(TranslateUtils.arrayToIpAddress(isIpv6, singleVxlanDetail.srcAddress));
        builder.setDst(TranslateUtils.arrayToIpAddress(isIpv6, singleVxlanDetail.dstAddress));
        // There are additional attributes of a vxlan tunnel that wont be used here
    }
}
