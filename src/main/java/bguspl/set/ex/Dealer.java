package bguspl.set.ex;

import bguspl.set.Env;

import java.text.CollationKey;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
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

    private boolean notify;
    private BlockingQueue<Integer> playersWaitBlockingQueue;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.notify = false;
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
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        for(Player player : players){
            player.terminate();
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
        // TODO implement
        for(int slot=0 ; slot<env.config.tableSize; slot++){
            if(table.shouldRemoveCard[slot]){
                table.removeCard(slot);
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        boolean placed = false;
        if(!deck.isEmpty()){
            for(int i=0 ; i<(table.slotToCard).length ; i++){
                if(!deck.isEmpty() && table.slotToCard[i] == null){
                    Integer card = deck.get(0);
                    deck.remove(0);
                    table.placeCard(card, i);
                    placed = true;
                }
            }
        }
        if(placed){
            updateTimerDisplay(true);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        // TODO implement
        long timer = System.currentTimeMillis();
        Integer playerId = -1;
        try {
            playerId = this.playersWaitBlockingQueue.poll(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        if(this.notify){
            //check and act
            Player player =idToPlayer(playerId);
            if(checkSet(playerId)){
                //point
                notifyPlayer(player, true);
            }
            else{
                //penalize

                notifyPlayer(player, false);
            }
        }
        this.notify = false;
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), false);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for(int i=0 ; i<(table.slotToCard).length ; i++){
            if(table.slotToCard[i] != null){
                Integer card = table.slotToCard[i];
                deck.add(card);
                table.removeCard(i);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }

    public void notifyDealer(int playerId) {
        this.notify = true;
        try {
            playersWaitBlockingQueue.add(playerId);
        } catch (IllegalStateException ignored) {}
    }

    public Player idToPlayer(int id) {
        for(Player player : players){
            if(player.getId() == id){
                return player;
            }
        }
        return null;
    }

    public boolean checkSet(int player) {
        int[] set = getSet(player);
        if(env.util.testSet(set)){
            table.shouldRemoveCard[table.cardToSlot[set[0]]] = true;
            table.shouldRemoveCard[table.cardToSlot[set[1]]] = true;
            table.shouldRemoveCard[table.cardToSlot[set[2]]] = true;
            return true;
        }
        table.removeToken(player, table.cardToSlot[set[0]]);
        table.removeToken(player, table.cardToSlot[set[1]]);
        table.removeToken(player, table.cardToSlot[set[2]]);
        return false;
    }

    private void notifyPlayer(Player player, boolean rulling) {
        player.notifyPlayer(rulling);
    }

    private int[] getSet(int player){
        int[] set = new int[3];
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