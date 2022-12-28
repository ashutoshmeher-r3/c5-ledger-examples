package c5.example.landregistry.contracts

import net.corda.v5.ledger.utxo.Command
import net.corda.v5.ledger.utxo.Contract
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction

class LandTitleContract: Contract {

    override fun verify(transaction: UtxoLedgerTransaction) {

    }

    class IssueLandTitle : Command
    class TransferLandTitle : Command

}