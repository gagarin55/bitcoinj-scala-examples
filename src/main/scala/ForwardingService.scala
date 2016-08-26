package com.wlangiewicz

import java.io.File

import com.google.common.base.Preconditions._
import com.google.common.util.concurrent.{FutureCallback, Futures, MoreExecutors}
import org.bitcoinj.core._
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.{MainNetParams, RegTestParams, TestNet3Params}
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.AbstractWalletEventListener;

/**
 * ForwardingService demonstrates basic usage of the library. It sits on the network and when it receives coins, simply
 * sends them onwards to an address given on the command line.
 *
 *  Usage:
 *    sbt "run-main com.wlangiewicz.ForwardingService address [regtest|testnet]"
 *  Usage example:
 *     sbt "run-main com.wlangiewicz.ForwardingService 17G3mZYjuNPhW267feDuVg3TmPBe6dEtEp"
 */

class Service(params: NetworkParameters, forwardingAddress: Address, filePrefix: String, kit: WalletAppKit) {
  def setupListeners(): Unit = {
    // We want to know when we receive money.
    kit.wallet.addEventListener(new AbstractWalletEventListener {
      override def onCoinsReceived(w: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
        val value: Coin = tx.getValueSentToMe(w)
        Console.println("Received tx for " + value.toFriendlyString + ": " + tx)
        Console.println("Transaction will be forwarded after it confirms.")

        // Wait until it's made it into the block chain (may run immediately if it's already there).
        //
        // For this dummy app of course, we could just forward the unconfirmed transaction. If it were
        // to be double spent, no harm done. Wallet.allowSpendingUnconfirmedTransactions() would have to
        // be called in onSetupCompleted() above. But we don't do that here to demonstrate the more common
        // case of waiting for a block.
        Futures.addCallback(tx.getConfidence().getDepthFuture(1), new FutureCallback[TransactionConfidence] {
          def onSuccess(result: TransactionConfidence) {
            forwardCoins(tx)
          }

          def onFailure(t: Throwable) {
            // This kind of future can't fail, just rethrow in case something weird happens.
            throw new RuntimeException(t)
          }
        })

      }
    })

    val sendToAddress: Address = kit.wallet.currentReceiveKey.toAddress(params)
    Console.println("Send coins to: " + sendToAddress)
    Console.println("Waiting for coins to arrive. Press Ctrl-C to quit.")
    try {
      Thread.sleep(Long.MaxValue)
    }
    catch {
      case ignored: InterruptedException => {
      }
    }

    Console.println("setupListeners finished")
  }

  def forwardCoins(tx: Transaction): Unit = {
    try {
      val value: Coin = tx.getValueSentToMe(kit.wallet)
      Console.println("Forwarding " + value.toFriendlyString)
      val amountToSend: Coin = value.subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE)
      val sendResult: Wallet.SendResult = kit.wallet.sendCoins(kit.peerGroup, forwardingAddress, amountToSend)
      checkNotNull(sendResult)
      Console.println("Sending ...")
      sendResult.broadcastComplete.addListener(new Runnable {
        def run {
          Console.println("Sent coins onwards! Transaction hash is " + sendResult.tx.getHashAsString)
        }
      }, MoreExecutors.sameThreadExecutor)
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
}

object ForwardingService extends App {

  def createService(args: Array[String]): Service = {
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
    val forwardingAddress = new Address(params, args(0))
    // Start up a basic app using a class that automates some boilerplate.
    val kit = new WalletAppKit(params, new File("."), filePrefix)
    if (params eq RegTestParams.get) {
      kit.connectToLocalHost
    }
    // Download the block chain and wait until it's done.
    kit.startAsync
    kit.awaitRunning

    new Service(params, forwardingAddress, filePrefix, kit)
  }

  override def main(args: Array[String]): Unit  = {

    if (args.length < 1) {
      Console.err.println("Usage: address-to-send-back-to [regtest|testnet]")
    }
    else{
      Console.println("Creating service")
      val service: Service = createService(args)
      Console.println("Setting up listeners")
      service.setupListeners()
    }
  }
}

