package org.mifos.connector.mojaloop.party;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.camel.trace.GetCachedTransactionIdProcessor;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.PartySwitchResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TENANT_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.camelHeadersToZeebeVariables;
import static org.mifos.connector.common.ams.dto.InteropIdentifierType.MSISDN;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class PartyLookupRoutes extends ErrorHandlerRouteBuilder {

    @Value("${bpmn.flows.party-lookup}")
    private String partyLookupFlow;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    private PartyProperties partyProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    @Autowired
    private AddTraceHeaderProcessor addTraceHeaderProcessor;

    @Autowired
    private GetCachedTransactionIdProcessor getCachedTransactionIdProcessor;

    @Autowired
    private PartiesResponseProcessor partiesResponseProcessor;

    public PartyLookupRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:GET:/switch/parties/{"+PARTY_ID_TYPE+"}/{"+PARTY_ID+"}")
                .log(LoggingLevel.WARN, "## SWITCH -> PAYEE inbound GET parties - STEP 2")
                .process(e ->
                        zeebeProcessStarter.startZeebeWorkflow(partyLookupFlow, null, variables -> {
                                camelHeadersToZeebeVariables(e, variables,
                                        PARTY_ID_TYPE,
                                        PARTY_ID,
                                        FSPIOP_SOURCE.headerName(),
                                        "traceparent",
                                        "Date");
                                variables.put(TENANT_ID, partyProperties.getParty(e.getIn().getHeader(PARTY_ID_TYPE, String.class),
                                        e.getIn().getHeader(PARTY_ID, String.class)).getTenantId());
                            }
                        )
                );

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYER - response for parties request  - STEP 3")
                .unmarshal().json(JsonLibrary.Jackson, PartySwitchResponseDTO.class)
                .process(getCachedTransactionIdProcessor)
                .process(partiesResponseProcessor);

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYER - parties error")
                .process(getCachedTransactionIdProcessor)
                .setProperty(PARTY_LOOKUP_FAILED, constant(true))
                .process(partiesResponseProcessor);

        from("direct:send-parties-response")
                .id("send-parties-response")
                .process(exchange -> {
                    Party party = objectMapper.readValue(exchange.getProperty(PAYEE_PARTY_RESPONSE, String.class), Party.class);

                    exchange.setProperty(PARTY_ID, party.getPartyIdInfo().getPartyIdentifier());
                    exchange.setProperty(PARTY_ID_TYPE, party.getPartyIdInfo().getPartyIdType().name());
                    exchange.getIn().setBody(new PartySwitchResponseDTO(party));
                    mojaloopUtil.setPartyHeadersResponse(exchange);
                })
                .process(pojoToString)
                .toD("rest:PUT:/parties/${exchangeProperty."+PARTY_ID_TYPE+"}/${exchangeProperty."+PARTY_ID+"}?host={{switch.host}}");

        from("direct:send-parties-error-response")
                .id("send-parties-error-response")
                .process(exchange -> {
                    exchange.setProperty(PARTY_ID, exchange.getIn().getHeader(PARTY_ID));
                    exchange.setProperty(PARTY_ID_TYPE, exchange.getIn().getHeader(PARTY_ID_TYPE));
                    exchange.getIn().setBody(exchange.getProperty(ERROR_INFORMATION));
                    mojaloopUtil.setPartyHeadersResponse(exchange);
                })
                .toD("rest:PUT:/parties/${exchangeProperty."+PARTY_ID_TYPE+"}/${exchangeProperty."+PARTY_ID+"}/error?host={{switch.host}}");

        from("direct:send-party-lookup")
                .id("send-party-lookup")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - party lookup request - STEP 1")
                .process(e -> {
                    TransactionChannelRequestDTO channelRequest = objectMapper.readValue(e.getProperty(TRANSACTION_REQUEST, String.class), TransactionChannelRequestDTO.class);
                    PartyIdInfo payeePartyIdInfo = channelRequest.getPayee().getPartyIdInfo();
                    e.setProperty(PARTY_ID_TYPE, payeePartyIdInfo.getPartyIdType());
                    e.setProperty(PARTY_ID, payeePartyIdInfo.getPartyIdentifier());

                    PartyIdInfo payerParty = channelRequest.getPayer().getPartyIdInfo();
                    String payerFspId = partyProperties.getParty(payerParty.getPartyIdType().name(), payerParty.getPartyIdentifier()).getFspId();
                    e.getIn().setHeader(FSPIOP_SOURCE.headerName(), payerFspId);

                    mojaloopUtil.setPartyHeadersRequest(e);
                })
                .process(addTraceHeaderProcessor)
                .toD("rest:GET:/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.host}}");
    }
}