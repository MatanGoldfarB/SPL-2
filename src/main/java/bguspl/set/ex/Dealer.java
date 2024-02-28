package bguspl.set.ex;

import bguspl.set.Env;

import java.text.CollationKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private BlockingQueue<Integer> playersWaitBlockingQueue;
    private static final int SLEEP_DURATION = 1000;
    private long timer = 0;
    protected Stack<Player> threadsCreated = new Stack<Player>();

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.playersWaitBlockingQueue = new LinkedBlockingQueue<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for(Player player : players){
            player.createThread();
        }
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            if(env.config.hints)
               table.hints();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    //60 sec
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            if(placeCardsOnTable() && env.config.turnTimeoutMillis <= 0){
                List<Integer> cardOnTable = new ArrayList<>();
                for (Integer card : table.slotToCard) {
                    if (card != null) {
                        cardOnTable.add(card);
                    }
                }
                if(env.util.findSets(cardOnTable, 1).size() == 0){
                    reshuffleTime = System.currentTimeMillis();
                }
            }
            else if(env.config.turnTimeoutMillis <= 0){
                List<Integer> cardOnTable = new ArrayList<>();
                for (Integer card : table.slotToCard) {
                    if (card != null) {
                        cardOnTable.add(card);
                    }
                }
                if(env.util.findSets(cardOnTable, 1).size() == 0){
                    reshuffleTime = System.currentTimeMillis();
                }
            }
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
            while (!threadsCreated.empty()) {
                Player tempPlayer = threadsCreated.pop();
                tempPlayer.terminate();
            }
        
        this.terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        synchronized(table){
            boolean removed = false;
            for(int slot=0 ; slot<env.config.tableSize; slot++){
                if(table.shouldRemoveCard[slot] && table.slotToCard[slot] != null){
                    LinkedList<Integer> tokens = table.tokensOnSlot.get(slot);
                    for(Integer player : tokens){
                        if(playersWaitBlockingQueue.remove(player)){
                            idToPlayer(player).notifyPlayer(-1);
                        }
                    }
                    table.removeCard(slot);
                    removed = true;
                }
                else{
                    table.shouldRemoveCard[slot] = false;
                }
            }
            if (removed) {
                updateTimerDisplay(true);
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private boolean placeCardsOnTable() {
        boolean placed = false;
        for(int i=0 ; i<(table.slotToCard).length ; i++){
            if(!deck.isEmpty() && table.slotToCard[i] == null){
                // removing the first card in the deck
                Integer card = deck.remove(0);
                table.placeCard(card, i);
                placed = true;
            }
        }
        if(placed){
            updateTimerDisplay(true);
        }
        return placed;
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        Integer playerId = null;
        try {
            playerId = this.playersWaitBlockingQueue.poll(env.config.tableDelayMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if(playerId != null){
            //check and act
            Player player =idToPlayer(playerId);
            if(checkSet(playerId)){
                //point
                player.notifyPlayer(1);
            }
            else{
                //penalize
                player.notifyPlayer(0);
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(env.config.turnTimeoutMillis > 0){
            if(reset){
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            }
            if(reshuffleTime-System.currentTimeMillis()<=env.config.turnTimeoutWarningMillis && reshuffleTime-System.currentTimeMillis()>=0)
               env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), true);
            else{
                env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), false);
            }
           
        } else if(env.config.turnTimeoutMillis == 0){
            if(reset){
                reshuffleTime = Long.MAX_VALUE;
                timer = System.currentTimeMillis();
            }
            env.ui.setCountdown(System.currentTimeMillis()-timer, false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        Arrays.fill(table.shouldRemoveCard, true);
        for(int i=0 ; i<(table.slotToCard).length ; i++){
            if(table.slotToCard[i] != null){
                Integer card = table.slotToCard[i];
                deck.add(card);
            }
        }        
        removeCardsFromTable();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        if(!terminate){
            terminate();
        }
        LinkedList<Integer> playersId = new LinkedList<>();
        int maxPoints = 0;
        for(Player player : players){
            if(player.score() > maxPoints){
                playersId.clear();
                playersId.add(player.id);
                maxPoints = player.score();
            } else if(player.score() == maxPoints){
                playersId.add(player.id);
            }
        }
        int[] playersArr = playersId.stream().mapToInt(Integer::intValue).toArray();
        env.ui.announceWinner(playersArr);
        try {
            Thread.sleep(env.config.endGamePauseMillies);
        } catch (InterruptedException ignored) {}
    }

    // Used from player to notify the dealer he needs to check the player's set
    public void notifyDealer(int playerId) {
        try {
            playersWaitBlockingQueue.add(playerId);
        } catch (IllegalStateException ignored) {}
    }

    // Gets a player id and returns the player object
    public Player idToPlayer(int id) {
        for(Player player : players){
            if(player.getId() == id){
                return player;
            }
        }
        return null;
    }

    // Gets a player and checks if his set is legal
    public boolean checkSet(int player) {
        synchronized(table){
            int[] set = getSet(player);
            if(env.util.testSet(set)){
                table.shouldRemoveCard[table.cardToSlot[set[0]]] = true;
                table.shouldRemoveCard[table.cardToSlot[set[1]]] = true;
                table.shouldRemoveCard[table.cardToSlot[set[2]]] = true;
                return true;
            }
            return false;
        }
    }

    // Gets a player and returning his tokens he placed
    private int[] getSet(int player){
        int[] set = new int[env.config.featureSize];
        int index=0;
        int slotIndex = 0;
        for(LinkedList<Integer> slot : table.tokensOnSlot){
            if (slot.contains(player)) {
                set[index] = table.slotToCard[slotIndex];
                index++;
            }
            slotIndex++;
        }
        return set;
    }
}