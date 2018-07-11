/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */
package org.apache.plc4x.java.ads.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.apache.plc4x.java.ads.api.commands.*;
import org.apache.plc4x.java.ads.api.commands.types.*;
import org.apache.plc4x.java.ads.api.generic.AmsPacket;
import org.apache.plc4x.java.ads.api.generic.types.AmsNetId;
import org.apache.plc4x.java.ads.api.generic.types.AmsPort;
import org.apache.plc4x.java.ads.api.generic.types.Invoke;
import org.apache.plc4x.java.ads.model.AdsAddress;
import org.apache.plc4x.java.ads.model.SymbolicAdsAddress;
import org.apache.plc4x.java.ads.protocol.exception.AdsException;
import org.apache.plc4x.java.api.exceptions.PlcException;
import org.apache.plc4x.java.api.exceptions.PlcIoException;
import org.apache.plc4x.java.api.exceptions.PlcProtocolException;
import org.apache.plc4x.java.api.messages.*;
import org.apache.plc4x.java.api.messages.items.ReadRequestItem;
import org.apache.plc4x.java.api.messages.items.ReadResponseItem;
import org.apache.plc4x.java.api.messages.items.WriteRequestItem;
import org.apache.plc4x.java.api.messages.items.WriteResponseItem;
import org.apache.plc4x.java.api.messages.specific.TypeSafePlcReadRequest;
import org.apache.plc4x.java.api.messages.specific.TypeSafePlcReadResponse;
import org.apache.plc4x.java.api.messages.specific.TypeSafePlcWriteRequest;
import org.apache.plc4x.java.api.messages.specific.TypeSafePlcWriteResponse;
import org.apache.plc4x.java.api.model.Address;
import org.apache.plc4x.java.api.types.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.apache.plc4x.java.ads.protocol.util.LittleEndianDecoder.decodeData;
import static org.apache.plc4x.java.ads.protocol.util.LittleEndianEncoder.encodeData;

public class Plc4x2AdsProtocol extends MessageToMessageCodec<AmsPacket, PlcRequestContainer<PlcRequest, PlcResponse>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Plc4x2AdsProtocol.class);

    private static final AtomicLong correlationBuilder = new AtomicLong(1);

    private final ConcurrentMap<Long, PlcRequestContainer<PlcRequest, PlcResponse>> requests;

    private final ConcurrentMap<SymbolicAdsAddress, AdsAddress> addressMapping;

    private List<Consumer<AdsDeviceNotificationRequest>> deviceNotificationListeners;

    private final AmsNetId targetAmsNetId;
    private final AmsPort targetAmsPort;
    private final AmsNetId sourceAmsNetId;
    private final AmsPort sourceAmsPort;

    public Plc4x2AdsProtocol(AmsNetId targetAmsNetId, AmsPort targetAmsPort, AmsNetId sourceAmsNetId, AmsPort sourceAmsPort, ConcurrentMap<SymbolicAdsAddress, AdsAddress> addressMapping) {
        this.targetAmsNetId = targetAmsNetId;
        this.targetAmsPort = targetAmsPort;
        this.sourceAmsNetId = sourceAmsNetId;
        this.sourceAmsPort = sourceAmsPort;
        this.requests = new ConcurrentHashMap<>();
        this.addressMapping = addressMapping;
        this.deviceNotificationListeners = new LinkedList<>();
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, PlcRequestContainer<PlcRequest, PlcResponse> msg, List<Object> out) throws Exception {
        LOGGER.trace("(<--OUT): {}, {}, {}", ctx, msg, out);
        PlcRequest request = msg.getRequest();
        if (request instanceof PlcReadRequest) {
            encodeReadRequest(msg, out);
        } else if (request instanceof PlcWriteRequest) {
            encodeWriteRequest(msg, out);
        } else if (request instanceof PlcProprietaryRequest) {
            encodeProprietaryRequest(msg, out);
        } else {
            throw new PlcProtocolException("Unknown type " + request.getClass());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.trace("(-->ERR): {}", ctx, cause);
        if (cause instanceof AdsException) {
            Invoke invokeId = ((AdsException) cause).getInvokeId();
            if (invokeId != null) {
                PlcRequestContainer<PlcRequest, PlcResponse> remove = requests.remove(invokeId.getAsLong());
                if (remove != null) {
                    remove.getResponseFuture().completeExceptionally(new PlcIoException(cause));
                } else {
                    LOGGER.warn("Unrelated exception received {}", invokeId, cause);
                }
            } else {
                super.exceptionCaught(ctx, cause);
            }
        } else if ((cause instanceof IOException) && (cause.getMessage().contains("Connection reset by peer") ||
            cause.getMessage().contains("Operation timed out"))) {
            String reason = cause.getMessage().contains("Connection reset by peer") ?
                "Connection terminated unexpectedly" : "Remote host not responding";
            if (!requests.isEmpty()) {
                // If the connection is hung up, all still pending requests can be closed.
                for (PlcRequestContainer requestContainer : requests.values()) {
                    requestContainer.getResponseFuture().completeExceptionally(new PlcIoException(reason));
                }
                // Clear the list
                requests.clear();
            }
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }

    private void encodeWriteRequest(PlcRequestContainer<PlcRequest, PlcResponse> msg, List<Object> out) throws PlcException {
        PlcWriteRequest writeRequest = (PlcWriteRequest) msg.getRequest();
        if (writeRequest.getRequestItems().size() != 1) {
            throw new PlcProtocolException("Only one item supported");
        }
        WriteRequestItem<?> writeRequestItem = writeRequest.getRequestItems().get(0);
        Address address = writeRequestItem.getAddress();
        if (address instanceof SymbolicAdsAddress) {
            AdsAddress mappedAddress = addressMapping.get(address);
            LOGGER.debug("Replacing {} with {}", address, mappedAddress);
            address = mappedAddress;
        }
        if (!(address instanceof AdsAddress)) {
            throw new PlcProtocolException("Address not of type AdsAddress: " + address.getClass());
        }
        AdsAddress adsAddress = (AdsAddress) address;
        Invoke invokeId = Invoke.of(correlationBuilder.incrementAndGet());
        IndexGroup indexGroup = IndexGroup.of(adsAddress.getIndexGroup());
        IndexOffset indexOffset = IndexOffset.of(adsAddress.getIndexOffset());
        byte[] bytes = encodeData(writeRequestItem.getDatatype(), writeRequestItem.getValues().toArray());
        Data data = Data.of(bytes);
        AmsPacket amsPacket = AdsWriteRequest.of(targetAmsNetId, targetAmsPort, sourceAmsNetId, sourceAmsPort, invokeId, indexGroup, indexOffset, data);
        LOGGER.debug("encoded write request {}", amsPacket);
        out.add(amsPacket);
        requests.put(invokeId.getAsLong(), msg);
    }

    private void encodeReadRequest(PlcRequestContainer<PlcRequest, PlcResponse> msg, List<Object> out) throws PlcException {
        PlcReadRequest readRequest = (PlcReadRequest) msg.getRequest();

        if (readRequest.getRequestItems().size() != 1) {
            throw new PlcProtocolException("Only one item supported");
        }
        ReadRequestItem<?> readRequestItem = readRequest.getRequestItems().get(0);
        Address address = readRequestItem.getAddress();
        if (address instanceof SymbolicAdsAddress) {
            AdsAddress mappedAddress = addressMapping.get(address);
            if (mappedAddress == null) {
                throw new PlcProtocolException("No address mapping for " + address);
            }
            LOGGER.debug("Replacing {} with {}", address, mappedAddress);
            address = mappedAddress;
        }
        if (!(address instanceof AdsAddress)) {
            throw new PlcProtocolException("Address not of type AdsAddress: " + address.getClass());
        }
        AdsAddress adsAddress = (AdsAddress) address;
        Invoke invokeId = Invoke.of(correlationBuilder.incrementAndGet());
        IndexGroup indexGroup = IndexGroup.of(adsAddress.getIndexGroup());
        IndexOffset indexOffset = IndexOffset.of(adsAddress.getIndexOffset());
        Length length = Length.of(readRequestItem.getSize());
        AmsPacket amsPacket = AdsReadRequest.of(targetAmsNetId, targetAmsPort, sourceAmsNetId, sourceAmsPort, invokeId, indexGroup, indexOffset, length);
        LOGGER.debug("encoded read request {}", amsPacket);
        out.add(amsPacket);
        requests.put(invokeId.getAsLong(), msg);
    }

    private void encodeProprietaryRequest(PlcRequestContainer<PlcRequest, PlcResponse> msg, List<Object> out) throws PlcProtocolException {
        PlcProprietaryRequest plcProprietaryRequest = (PlcProprietaryRequest) msg.getRequest();
        if (!(plcProprietaryRequest.getRequest() instanceof AmsPacket)) {
            throw new PlcProtocolException("Unsupported proprietary type for this driver " + plcProprietaryRequest.getRequest().getClass());
        }
        AmsPacket amsPacket = (AmsPacket) plcProprietaryRequest.getRequest();
        LOGGER.debug("encoded proprietary request {}", amsPacket);
        out.add(amsPacket);
        requests.put(amsPacket.getAmsHeader().getInvokeId().getAsLong(), msg);
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, AmsPacket amsPacket, List<Object> out) throws Exception {
        LOGGER.trace("(-->IN): {}, {}, {}", channelHandlerContext, amsPacket, out);
        if (amsPacket instanceof AdsDeviceNotificationRequest) {
            LOGGER.debug("Received notification {}", amsPacket);
            handleAdsDeviceNotificationRequest((AdsDeviceNotificationRequest) amsPacket);
            return;
        }
        PlcRequestContainer<PlcRequest, PlcResponse> plcRequestContainer = requests.remove(amsPacket.getAmsHeader().getInvokeId().getAsLong());
        if (plcRequestContainer == null) {
            LOGGER.info("Unmapped packet received {}", amsPacket);
            return;
        }
        PlcRequest request = plcRequestContainer.getRequest();
        PlcResponse response = null;

        // Handle the response to a read request.
        if (request instanceof PlcReadRequest) {
            if (amsPacket instanceof AdsReadResponse) {
                response = decodeReadResponse((AdsReadResponse) amsPacket, plcRequestContainer);
            } else {
                throw new PlcProtocolException("Wrong type correlated " + amsPacket);
            }
        } else if (request instanceof PlcWriteRequest) {
            if (amsPacket instanceof AdsWriteResponse) {
                response = decodeWriteResponse((AdsWriteResponse) amsPacket, plcRequestContainer);
            } else {
                throw new PlcProtocolException("Wrong type correlated " + amsPacket);
            }
        } else if (request instanceof PlcProprietaryRequest) {
            response = decodeProprietaryResponse(amsPacket, plcRequestContainer);
        }
        LOGGER.debug("Plc4x response {}", response);

        // Confirm the response being handled.
        if (response != null) {
            plcRequestContainer.getResponseFuture().complete(response);
        }
    }

    private void handleAdsDeviceNotificationRequest(AdsDeviceNotificationRequest adsDeviceNotificationRequest) {
        for (Consumer<AdsDeviceNotificationRequest> deviceNotificationListener : deviceNotificationListeners) {
            try {
                deviceNotificationListener.accept(adsDeviceNotificationRequest);
            } catch (RuntimeException e) {
                LOGGER.error("Exception received from {} while handling {}", deviceNotificationListener, adsDeviceNotificationRequest, e);
            }
        }
    }

    public boolean addConsumer(Consumer<AdsDeviceNotificationRequest> adsDeviceNotificationRequestConsumer) {
        return deviceNotificationListeners.add(adsDeviceNotificationRequestConsumer);
    }

    public boolean removeConsumer(Consumer<AdsDeviceNotificationRequest> adsDeviceNotificationRequestConsumer) {
        return deviceNotificationListeners.remove(adsDeviceNotificationRequestConsumer);
    }


    @SuppressWarnings("unchecked")
    private PlcResponse decodeWriteResponse(AdsWriteResponse responseMessage, PlcRequestContainer<PlcRequest, PlcResponse> requestContainer) {
        PlcWriteRequest plcWriteRequest = (PlcWriteRequest) requestContainer.getRequest();
        WriteRequestItem requestItem = plcWriteRequest.getRequestItems().get(0);

        ResponseCode responseCode = decodeResponseCode(responseMessage.getResult());

        if (plcWriteRequest instanceof TypeSafePlcWriteRequest) {
            return new TypeSafePlcWriteResponse((TypeSafePlcWriteRequest) plcWriteRequest, Collections.singletonList(new WriteResponseItem<>(requestItem, responseCode)));
        } else {
            return new PlcWriteResponse(plcWriteRequest, Collections.singletonList(new WriteResponseItem<>(requestItem, responseCode)));
        }
    }

    @SuppressWarnings("unchecked")
    private PlcResponse decodeReadResponse(AdsReadResponse responseMessage, PlcRequestContainer<PlcRequest, PlcResponse> requestContainer) throws PlcProtocolException {
        PlcReadRequest plcReadRequest = (PlcReadRequest) requestContainer.getRequest();
        ReadRequestItem requestItem = plcReadRequest.getRequestItems().get(0);

        ResponseCode responseCode = decodeResponseCode(responseMessage.getResult());
        byte[] bytes = responseMessage.getData().getBytes();
        List decoded = decodeData(requestItem.getDatatype(), bytes);

        if (plcReadRequest instanceof TypeSafePlcReadRequest) {
            return new TypeSafePlcReadResponse((TypeSafePlcReadRequest) plcReadRequest, Collections.singletonList(new ReadResponseItem<>(requestItem, responseCode, decoded)));
        } else {
            return new PlcReadResponse(plcReadRequest, Collections.singletonList(new ReadResponseItem<>(requestItem, responseCode, decoded)));
        }
    }

    private PlcResponse decodeProprietaryResponse(AmsPacket amsPacket, PlcRequestContainer<PlcRequest, PlcResponse> plcRequestContainer) {
        return new PlcProprietaryResponse<>((PlcProprietaryRequest) plcRequestContainer.getRequest(), amsPacket);
    }

    private ResponseCode decodeResponseCode(Result result) {
        switch (result.toAdsReturnCode()) {
            case ADS_CODE_0:
                return ResponseCode.OK;
            case ADS_CODE_1:
                return ResponseCode.INTERNAL_ERROR;
            case ADS_CODE_2:
            case ADS_CODE_3:
            case ADS_CODE_4:
            case ADS_CODE_5:
                return ResponseCode.INTERNAL_ERROR;
            case ADS_CODE_6:
            case ADS_CODE_7:
                return ResponseCode.INVALID_ADDRESS;
            case ADS_CODE_8:
            case ADS_CODE_9:
            case ADS_CODE_10:
            case ADS_CODE_11:
            case ADS_CODE_12:
            case ADS_CODE_13:
            case ADS_CODE_14:
            case ADS_CODE_15:
            case ADS_CODE_16:
            case ADS_CODE_17:
            case ADS_CODE_18:
            case ADS_CODE_19:
            case ADS_CODE_20:
            case ADS_CODE_21:
            case ADS_CODE_22:
            case ADS_CODE_23:
            case ADS_CODE_24:
            case ADS_CODE_25:
            case ADS_CODE_26:
            case ADS_CODE_27:
            case ADS_CODE_28:
            case ADS_CODE_1280:
            case ADS_CODE_1281:
            case ADS_CODE_1282:
            case ADS_CODE_1283:
            case ADS_CODE_1284:
            case ADS_CODE_1285:
            case ADS_CODE_1286:
            case ADS_CODE_1287:
            case ADS_CODE_1288:
            case ADS_CODE_1289:
            case ADS_CODE_1290:
            case ADS_CODE_1291:
            case ADS_CODE_1292:
            case ADS_CODE_1293:
            case ADS_CODE_1792:
            case ADS_CODE_1793:
            case ADS_CODE_1794:
            case ADS_CODE_1795:
            case ADS_CODE_1796:
            case ADS_CODE_1797:
            case ADS_CODE_1798:
            case ADS_CODE_1799:
            case ADS_CODE_1800:
            case ADS_CODE_1801:
            case ADS_CODE_1802:
            case ADS_CODE_1803:
            case ADS_CODE_1804:
            case ADS_CODE_1805:
            case ADS_CODE_1806:
            case ADS_CODE_1807:
            case ADS_CODE_1808:
            case ADS_CODE_1809:
            case ADS_CODE_1810:
            case ADS_CODE_1811:
            case ADS_CODE_1812:
            case ADS_CODE_1813:
            case ADS_CODE_1814:
            case ADS_CODE_1815:
            case ADS_CODE_1816:
            case ADS_CODE_1817:
            case ADS_CODE_1818:
            case ADS_CODE_1819:
            case ADS_CODE_1820:
            case ADS_CODE_1821:
            case ADS_CODE_1822:
            case ADS_CODE_1823:
            case ADS_CODE_1824:
            case ADS_CODE_1825:
            case ADS_CODE_1826:
            case ADS_CODE_1827:
            case ADS_CODE_1828:
            case ADS_CODE_1836:
            case ADS_CODE_1856:
            case ADS_CODE_1857:
            case ADS_CODE_1858:
            case ADS_CODE_1859:
            case ADS_CODE_1860:
            case ADS_CODE_1861:
            case ADS_CODE_1862:
            case ADS_CODE_1863:
            case ADS_CODE_1864:
            case ADS_CODE_1872:
            case ADS_CODE_1873:
            case ADS_CODE_1874:
            case ADS_CODE_1875:
            case ADS_CODE_1876:
            case ADS_CODE_1877:
            case ADS_CODE_4096:
            case ADS_CODE_4097:
            case ADS_CODE_4098:
            case ADS_CODE_4099:
            case ADS_CODE_4100:
            case ADS_CODE_4101:
            case ADS_CODE_4102:
            case ADS_CODE_4103:
            case ADS_CODE_4104:
            case ADS_CODE_4105:
            case ADS_CODE_4106:
            case ADS_CODE_4107:
            case ADS_CODE_4108:
            case ADS_CODE_4109:
            case ADS_CODE_4110:
            case ADS_CODE_4111:
            case ADS_CODE_4112:
            case ADS_CODE_4119:
            case ADS_CODE_4120:
            case ADS_CODE_4121:
            case ADS_CODE_4122:
            case ADS_CODE_10060:
            case ADS_CODE_10061:
            case ADS_CODE_10065:
                return ResponseCode.INTERNAL_ERROR;
            case UNKNOWN:
                return ResponseCode.INTERNAL_ERROR;
        }
        throw new IllegalStateException(result.toAdsReturnCode() + " not mapped");
    }

}
