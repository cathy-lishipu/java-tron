package org.tron.consensus.dpos;

import static org.tron.consensus.base.Constant.BLOCK_PRODUCED_INTERVAL;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.ECKey.ECDSASignature;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.consensus.ConsensusDelegate;
import org.tron.consensus.base.Param.Miner;
import org.tron.consensus.base.State;
import org.tron.core.capsule.AccountCapsule;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.BlockHeader;

@Slf4j(topic = "consensus")
@Component
public class DposTask {

  @Autowired
  private ConsensusDelegate consensusDelegate;

  @Autowired
  private DposSlot dposSlot;

  @Autowired
  private StateManager stateManager;

  @Setter
  private DposService dposService;

  private Thread produceThread;

  private volatile boolean isRunning = true;

  public void init() {

    Runnable runnable = () -> {
      while (isRunning) {
        try {
          if (dposService.isNeedSyncCheck()) {
            Thread.sleep(1000);
            dposService.setNeedSyncCheck(dposSlot.getTime(1) < System.currentTimeMillis());
          } else {
            long time =
                BLOCK_PRODUCED_INTERVAL - System.currentTimeMillis() % BLOCK_PRODUCED_INTERVAL;
            Thread.sleep(time);
            State state = produceBlock();
            if (!State.OK.equals(state)) {
              logger.info("Produce block failed: {}", state);
            }
          }
        } catch (Throwable throwable) {
          logger.error("Produce block error.", throwable);
        }
      }
    };
    produceThread = new Thread(runnable, "DPosMiner");
    produceThread.start();
    logger.info("DPoS service stared.");
  }

  public void stop() {
    isRunning = false;
    produceThread.interrupt();
    logger.info("DPoS service stopped.");
  }

  private State produceBlock() {

    State state = stateManager.getState();
    if (!State.OK.equals(state)) {
      return state;
    }

    synchronized (dposService.getBlockHandle().getLock()) {

      long slot = dposSlot.getSlot(System.currentTimeMillis() + 50);
      if (slot == 0) {
        return State.NOT_TIME_YET;
      }

      final ByteString scheduledWitness = dposSlot.getScheduledWitness(slot);
      state = stateManager.getState(scheduledWitness);
      if (!State.OK.equals(state)) {
        return state;
      }

      Block block = dposService.getBlockHandle().produce();
      if (block == null) {
        return State.PRODUCE_BLOCK_FAILED;
      }

      Block sBlock = getSignedBlock(block, scheduledWitness, slot);

      stateManager.setCurrentBlock(sBlock);

      dposService.getBlockHandle().complete(sBlock);

      BlockHeader.raw raw = sBlock.getBlockHeader().getRawData();
      logger.info("Produce block successfully, num:{}, time:{}, witness:{}, hash:{} parentHash:{}",
          raw.getNumber(),
          new DateTime(raw.getTimestamp()),
          raw.getWitnessAddress(),
          DposService.getBlockHash(sBlock),
          ByteArray.toHexString(raw.getParentHash().toByteArray()));
    }

    return State.OK;
  }

  public Block getSignedBlock(Block block, ByteString witness, long slot) {
    BlockHeader.raw raw = block.getBlockHeader().getRawData().toBuilder()
        .setParentHash(ByteString.copyFrom(consensusDelegate.getLatestBlockHeaderHash().getBytes()))
        .setNumber(consensusDelegate.getLatestBlockHeaderNumber() + 1)
        .setTimestamp(dposSlot.getTime(slot))
        .setWitnessAddress(witness)
        .build();

    ECKey ecKey = ECKey.fromPrivate(dposService.getMiners().get(witness).getPrivateKey());
    ECDSASignature signature = ecKey.sign(Sha256Hash.of(raw.toByteArray()).getBytes());
    ByteString sign = ByteString.copyFrom(signature.toByteArray());

    BlockHeader blockHeader = block.getBlockHeader().toBuilder()
        .setRawData(raw)
        .setWitnessSignature(sign)
        .build();

    Block signedBlock = block.toBuilder().setBlockHeader(blockHeader).build();

    return signedBlock;
  }

}
