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
import net.corda.v5.application.messaging.receive
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.days
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import java.time.Instant
import java.time.LocalDateTime

@InitiatingFlow("transfer-title")
class TransferLandTitleFlow : RPCStartableFlow {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        val request = requestBody.getRequestBodyAs<TransferLandTitleRequest>(jsonMarshallingService)
        val myInfo = memberLookup.myInfo()
        val owner = memberLookup.lookup(request.owner)
            ?: throw IllegalArgumentException("Unknown holder: ${request.owner}.")

        // Unable to fetch old state
        val oldState = utxoLedgerService.findUnconsumedStatesByType(LandTitleState::class.java).filter {
            it.state.contractState.titleNumber.equals(request.titleNumber)
        }.first()

        val landTitleState = LandTitleState(
            oldState.state.contractState.titleNumber,
            oldState.state.contractState.location,
            oldState.state.contractState.areaInSquareMeter,
            oldState.state.contractState.extraDetails,
            LocalDateTime.now(),
            owner.ledgerKeys.first(),
            myInfo.ledgerKeys.first()
        )

        val transaction = utxoLedgerService
            .getTransactionBuilder()
            .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(1.days.toMillis()))
            .setNotary(oldState.state.notary)
            .addInputState(oldState.ref)
            .addOutputState(landTitleState)
            .addCommand(LandTitleContract.TransferLandTitle())
            .addSignatories(listOf(landTitleState.issuer, landTitleState.owner, oldState.state.contractState.owner))

        @Suppress("DEPRECATION")
        val partiallySignedTransaction = transaction.toSignedTransaction(myInfo.ledgerKeys.first())

        val issuer = memberLookup.lookup(oldState.state.contractState.issuer)
            ?: throw IllegalArgumentException("Unknown Issuer: ${oldState.state.contractState.issuer}.")
        val issuerSession = flowMessaging.initiateFlow(issuer.name)

        issuerSession.send(partiallySignedTransaction)
        val fullySignedTransaction = issuerSession.receive<UtxoSignedTransaction>();

        val finalizedSignedTransaction = utxoLedgerService.finalize(
            fullySignedTransaction,
            listOf(issuerSession)
        )

        return finalizedSignedTransaction.id.toString()

    }
}

@InitiatedBy("transfer-title")
class TransferLandTitleResponderFlow: ResponderFlow {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    override fun call(session: FlowSession) {
        val partiallySignedTransaction = session.receive<UtxoSignedTransaction>()
        //TODO Add Signature - Cant find api to add signature
        session.send(partiallySignedTransaction)
        utxoLedgerService.receiveFinality(session) {}
    }
}

data class TransferLandTitleRequest(
    val titleNumber: String,
    val owner: MemberX500Name
)