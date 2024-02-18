package bguspl.set.ex;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.LinkedList;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    private BlockingQueue<Integer> actionsQueue;
    private int rulling = -1;
    private static final int SLEEP_DURATION = 1000;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.actionsQueue = new ArrayBlockingQueue<>(env.config.featureSize);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            try {
                Integer slot = this.actionsQueue.take();
                if(table.tokensOnSlot.get(slot).contains(id)){
                    table.removeToken(id, slot);
                }
                else{
                    if(numTokensPlaced() != env.config.featureSize){
                        this.table.placeToken(id, slot);
                        if(numTokensPlaced() == env.config.featureSize){
                            //tell dealer to check
                            synchronized(this){
                                // Wait for notification from Dealer
                                dealer.notifyDealer(id);
                                this.wait();
                                // Perform action upon notification
                                if(this.rulling == 1){
                                    point();
                                } else if(this.rulling == 0){
                                    penalty();
                                }
                                if(human){
                                    actionsQueue.clear();
                                }
                                // Reset the notification flag
                                this.rulling = -1;
                            }
                        }
                    }
                }
            } catch (InterruptedException ignored) {}
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rnd = new Random();
            while (!terminate) {
                int randomSlot = rnd.nextInt(env.config.tableSize);
                keyPressed(randomSlot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate=true;
        try {
            if (!human) {
                aiThread.interrupt();   
                aiThread.join();
            }
            playerThread.interrupt();
            playerThread.join();
        } catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        try {
            actionsQueue.put(slot);
        } catch (InterruptedException ignored) {}
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        long freezeTimeLeft = env.config.pointFreezeMillis;
        try {
            while(freezeTimeLeft>=0){
                env.ui.setFreeze(id, freezeTimeLeft);
                Thread.sleep(SLEEP_DURATION);
                freezeTimeLeft -= SLEEP_DURATION;
            }
            env.ui.setFreeze(id, freezeTimeLeft);
        } catch (InterruptedException e) {}
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long freezeTimeLeft = env.config.penaltyFreezeMillis;
        try {
            while(freezeTimeLeft>0){
                env.ui.setFreeze(id, freezeTimeLeft);
                Thread.sleep(SLEEP_DURATION);
                freezeTimeLeft -= SLEEP_DURATION;
            }
            env.ui.setFreeze(id, freezeTimeLeft);
        } catch (InterruptedException e) {}
    }

    public int score() {
        return score;
    }

    public void createThread() {
        this.playerThread = new Thread(this);
        this.playerThread.start();
    }

    public void notifyPlayer(int rulling) {
        synchronized (this) {
            this.rulling = rulling;
            this.notify(); // Notify all waiting players
        }
    }

    public int getId(){
        return id;
    }

    public int numTokensPlaced(){
        int count = 0;
        for(LinkedList<Integer> slot : table.tokensOnSlot){
            if (slot.contains(id)) {
                count++;
            }
        }
        return count;
    }
}
