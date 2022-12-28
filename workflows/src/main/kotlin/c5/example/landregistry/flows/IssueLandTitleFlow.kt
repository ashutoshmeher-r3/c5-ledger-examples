package c5.example.landregistry.flows

import c5.example.landregistry.contracts.LandTitleContract
import c5.example.landregistry.states.LandTitleState
import net.corda.v5.application.flows.ResponderFlow
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.RPCStartableFlow
import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.RPCRequestData
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.getRequestBodyAs
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.days
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.UtxoLedgerService
import java.time.Instant
import java.time.LocalDateTime

@InitiatingFlow("issue-title")
class IssueLandTitleFlow: RPCStartableFlow {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        val request = requestBody.getRequestBodyAs<LandRegistryRequest>(jsonMarshallingService)

        //Results in error: org.apache.avro.UnresolvedUnionException: Not in union
        //if(utxoLedgerService.findUnconsumedStatesByType(LandTitleState::class.java).isNotEmpty())
        //    throw IllegalArgumentException("Title Number: ${request.titleNumber} already exist.")

        val myInfo = memberLookup.myInfo()
        val owner = memberLookup.lookup(request.owner)
            ?: throw IllegalArgumentException("Unknown holder: ${request.owner}.")

        val landTitleState = LandTitleState(
            request.titleNumber,
            request.location,
            request.areaInSquareMeter,
            request.extraDetails,
            LocalDateTime.now(),
            owner.ledgerKeys.first(),
            myInfo.ledgerKeys.first()
        )

        // CORE-6173 Cannot use proper notary key
        val notary = notaryLookup.notaryServices.first()
        val notaryKey = memberLookup.lookup().first {
            it.memberProvidedContext["corda.notary.service.name"] == notary.name.toString()
        }.ledgerKeys.first()

        val transaction = utxoLedgerService
            .getTransactionBuilder()
            .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(1.days.toMillis()))
            .setNotary(Party(notary.name, notaryKey))
            .addOutputState(landTitleState)
            .addCommand(LandTitleContract.IssueLandTitle())
            .addSignatories(listOf(landTitleState.issuer))

        @Suppress("DEPRECATION") // Parameterless function not implemented yet.
        val signedTransaction = transaction.toSignedTransaction(myInfo.ledgerKeys.first())

        val flowSession = flowMessaging.initiateFlow(owner.name)

        val finalizedSignedTransaction = utxoLedgerService.finalize(
            signedTransaction,
            listOf(flowSession)
        )

        return finalizedSignedTransaction.id.toString()
    }
}

@InitiatedBy("issue-title")
class IssueLandTitleResponderFlow: ResponderFlow {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    override fun call(session: FlowSession) {
        utxoLedgerService.receiveFinality(session) {}

    }
}

data class LandRegistryRequest(
    val titleNumber: String,
    val location: String,
    val areaInSquareMeter: Int,
    val extraDetails: String,
    val owner: MemberX500Name
)
