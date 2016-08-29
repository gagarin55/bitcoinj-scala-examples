package com.wlangiewicz

import java.io.File

import com.google.common.base.Preconditions._
import com.google.common.util.concurrent.{FutureCallback, Futures, MoreExecutors}
import org.bitcoinj.core._
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.{MainNetParams, RegTestParams, TestNet3Params}
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.{AbstractWalletEventListener, WalletCoinsReceivedEventListener};

/**
 * ForwardingService demonstrates basic usage of the library. It sits on the network and when it receives coins, simply
 * sends them onwards to an address given on the command line.
 *
 *  Usage:
 *    sbt "run-main com.wlangiewicz.ForwardingService address [regtest|testnet]"
 *  Usage example:
 *     sbt "run-main com.wlangiewicz.ForwardingService 17G3mZYjuNPhW267feDuVg3TmPBe6dEtEp"
 */
object ForwardingService extends App {


  def forwardCoins(kit: WalletAppKit, tx: Transaction, forwardingAddress: Address): Unit = {
    try {
      val value: Coin = tx.getValueSentToMe(kit.wallet)
      println("Forwarding " + value.toFriendlyString)
      // Now send the coins back! Send with a small fee attached to ensure rapid confirmation.
      val amountToSend: Coin = value.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE)
      val sendResult: Wallet.SendResult = kit.wallet.sendCoins(kit.peerGroup, forwardingAddress, amountToSend)
      checkNotNull(sendResult)
      println("Sending ...")
      // Register a callback that is invoked when the transaction has propagated across the network.
      // This shows a second style of registering ListenableFuture callbacks, it works when you don't
      // need access to the object the future returns.
      sendResult.broadcastComplete.addListener(new Runnable {
        def run {
          println("Sent coins onwards! Transaction hash is " + sendResult.tx.getHashAsString)
        }
      }, MoreExecutors.directExecutor)
    }
    catch {
      case e: KeyCrypterException => {
        throw new RuntimeException(e)
      }
      case e: InsufficientMoneyException => {
        throw new RuntimeException(e)
      }
    }
  }

  override def main(args: Array[String]): Unit  = {

    if (args.length < 1) {
      Console.err.println("Usage: address-to-send-back-to [regtest|testnet]")
    }
    else {
      var params: NetworkParameters = null
      var filePrefix: String = null

      // Figure out which network we should connect to. Each one gets its own set of files.
      if (args.length > 1 && (args(1) == "testnet")) {
        params = TestNet3Params.get
        filePrefix = "forwarding-service-testnet"
      }
      else if (args.length > 1 && (args(1) == "regtest")) {
        params = RegTestParams.get
        filePrefix = "forwarding-service-regtest"
      }
      else {
        params = MainNetParams.get
        filePrefix = "forwarding-service"
      }
      // Parse the address given as the first parameter.
      val forwardingAddress = Address.fromBase58(params, args(0))
      // Start up a basic app using a class that automates some boilerplate.
      val kit = new WalletAppKit(params, new File("."), filePrefix)
      if (params eq RegTestParams.get) {
        kit.connectToLocalHost
      }
      // Download the block chain and wait until it's done.
      kit.startAsync
      kit.awaitRunning


      // We want to know when we receive money.
      kit.wallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener {
        override def onCoinsReceived(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin): Unit = {

          // Runs in the dedicated "user thread" (see bitcoinj docs for more info on this).
          //
          // The transaction "tx" can either be pending, or included into a block (we didn't see the broadcast).
          val value = tx.getValueSentToMe(wallet)
          println("Received tx for " + value.toFriendlyString() + ": " + tx)
          println("Transaction will be forwarded after it confirms.")

          // Wait until it's made it into the block chain (may run immediately if it's already there).
          //
          // For this dummy app of course, we could just forward the unconfirmed transaction. If it were
          // to be double spent, no harm done. Wallet.allowSpendingUnconfirmedTransactions() would have to
          // be called in onSetupCompleted() above. But we don't do that here to demonstrate the more common
          // case of waiting for a block.
          Futures.addCallback(tx.getConfidence().getDepthFuture(1), new FutureCallback[TransactionConfidence] {
            def onSuccess(result: TransactionConfidence) {
              forwardCoins(kit, tx, forwardingAddress)
            }

            def onFailure(t: Throwable) {
              // This kind of future can't fail, just rethrow in case something weird happens.
              throw new RuntimeException(t)
            }
          })
        }
      })

      println("Addresses in Wallet")
      kit.wallet.getIssuedReceiveAddresses.toArray.foreach(a => {
        println(a)
      })

      val balance = kit.wallet.getBalance
      println("Current available balance: "+balance.toFriendlyString)
      val sendToAddress: Address = kit.wallet.currentReceiveKey.toAddress(params)
      println("Send coins to: " + sendToAddress)
      println("Waiting for coins to arrive. Press Ctrl-C to quit.")
      try {
        Thread.sleep(Long.MaxValue)
      }
      catch {
        case ignored: InterruptedException => {
        }
      }
    }
  }
}

