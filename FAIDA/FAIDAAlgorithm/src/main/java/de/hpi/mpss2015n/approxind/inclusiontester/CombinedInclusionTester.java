package de.hpi.mpss2015n.approxind.inclusiontester;

import de.hpi.mpss2015n.approxind.InclusionTester;
import de.hpi.mpss2015n.approxind.datastructures.HyperLogLog;
import de.hpi.mpss2015n.approxind.datastructures.RegisterSet;
import de.hpi.mpss2015n.approxind.utils.ColumnStore;
import de.hpi.mpss2015n.approxind.utils.SimpleColumnCombination;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;

/**
 * IND test that employs a mixture of a sample-based inverted index and some approximate data structure.
 */
abstract public class CombinedInclusionTester<AD> implements InclusionTester {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Nested IND test via an inverted index as proposed by DeMarchi et al.
     */
    private final SampledInvertedIndex sampledInvertedIndex;

    /**
     * Map table IDs to the HLL structures of their column combinations.
     */
    protected final Int2ObjectMap<Map<SimpleColumnCombination, AD>> adByTable = new Int2ObjectOpenHashMap<>();

    /**
     * {@link #adByTable} as array (for performance reasons).
     */
    private Map.Entry<SimpleColumnCombination, AD>[] adByTableArray;


    public CombinedInclusionTester() {
        sampledInvertedIndex = new SampledInvertedIndex();
    }

    @Override
    public int[] setColumnCombinations(List<SimpleColumnCombination> combinations) {
        adByTable.clear();
        int[] activeTables = combinations.stream()
                .mapToInt(SimpleColumnCombination::getTable)
                .distinct().sorted().toArray();
        for (int table : activeTables) {
            adByTable.put(table, new HashMap<>());
        }
        for (SimpleColumnCombination combination : combinations) {
            adByTable.get(combination.getTable()).put(combination, this.createApproximateDatastructures(combination));
        }
        return activeTables;
    }

    abstract protected AD createApproximateDatastructures(SimpleColumnCombination combination);

    @Override
    public void initialize(List<List<long[]>> tableSamplesList) {
        // Collect all column combinations that can be inserted.
        List<SimpleColumnCombination> combinations = new ArrayList<>();
        for (Map<SimpleColumnCombination, AD> hllsByColumnCombo : adByTable.values()) {
            combinations.addAll(hllsByColumnCombo.keySet());
        }
        for (int i = 0; i < combinations.size(); i++) {
            combinations.get(i).setIndex(i);
        }
        sampledInvertedIndex.setMaxIndex(combinations.size() - 1);

        // Now create according hashes from the table samples and initialize the inverted index with them.
        LongList samples = new LongArrayList();
        for (int table = 0; table < tableSamplesList.size(); table++) {
            Map<SimpleColumnCombination, AD> adByColumnCombinations = adByTable.get(table);
            if (adByColumnCombinations != null) {
                List<long[]> tableSamples = tableSamplesList.get(table);
                for (long[] sampleRow : tableSamples) {
                    for (Map.Entry<SimpleColumnCombination, AD> entry : adByColumnCombinations.entrySet()) {
                        SimpleColumnCombination combination = entry.getKey();
                        long combinedHash = 0;
                        int[] columns = combination.getColumns();
                        boolean anyNull = false;
                        for (int i = 0; i < columns.length; i++) {
                            long hash = sampleRow[columns[i]];
                            if (anyNull = hash == ColumnStore.NULLHASH) break;
                            combinedHash = Long.rotateLeft(combinedHash, 1) ^ hash;
                        }
                        if (!anyNull) {
                            samples.add(combinedHash);
                        }
                    }

                }
            }
        }
        sampledInvertedIndex.initialize(samples);
    }

    @Override
    public void finalizeInsertion() {
        sampledInvertedIndex.finalizeInsertion(adByTable.values());
    }

    @Override
    public void insertRow(long[] values, int rowCount) {
        ColumnCombinations:
        for (Entry<SimpleColumnCombination, AD> entry : adByTableArray) {

            // Combine the hash values.
            SimpleColumnCombination combination = entry.getKey();
            long combinedHash = 0;
            int[] columns = combination.getColumns();
            for (int i = 0; i < columns.length; i++) {
                long hash = values[columns[i]];
                if (hash == ColumnStore.NULLHASH) continue ColumnCombinations;
                combinedHash = Long.rotateLeft(combinedHash, 1) ^ hash;
            }

            // Update the data summaries.
            if (!sampledInvertedIndex.update(combination, combinedHash)) {
                this.insertRowIntoAD(combination, combinedHash, entry.getValue());
            }
        }
    }

    protected abstract void insertRowIntoAD(SimpleColumnCombination combination, long hash, AD value);

    @Override
    public boolean isIncludedIn(SimpleColumnCombination a, SimpleColumnCombination b) {
        //In case combination was removes - Todo: test if ind is valid based on generated ind cover
        if (!adByTable.get(a.getTable()).containsKey(a) || !adByTable.get(b.getTable()).containsKey(b)) {
            return false;
        }

        boolean isACovered = this.sampledInvertedIndex.isCovered(a);
        boolean isBCovered = this.sampledInvertedIndex.isCovered(b);

        if (!isACovered && isBCovered) {
            return false;
        } else {
            AD adA = adByTable.get(a.getTable()).get(a);
            AD adB = adByTable.get(b.getTable()).get(b);

            if (!this.testWithAds(adA, adB)) {
                return false;
            }
        }

        return sampledInvertedIndex.isIncludedIn(a, b);
    }

    protected abstract boolean testWithAds(AD adA, AD adB);

    private int[] getRegisterSetBits(HyperLogLog hll) {
        return hll.registerSet().readOnlyBits();
    }

    //tested to be 25% faster than merging the registerSets
    private boolean isIncluded(HyperLogLog a, HyperLogLog b) {
        int[] aBits = getRegisterSetBits(a);
        int[] bBits = getRegisterSetBits(b);
        for (int bucket = 0; bucket < bBits.length; ++bucket) {
            int aBit = aBits[bucket];
            int bBit = bBits[bucket];
            for (int j = 0; j < RegisterSet.LOG2_BITS_PER_WORD; ++j) {
                int mask = 31 << (RegisterSet.REGISTER_SIZE * j);
                int aVal = aBit & mask;
                int bVal = bBit & mask;
                if (bVal < aVal) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void startInsertRow(int table) {
        Set<Entry<SimpleColumnCombination, AD>> set = adByTable.get(table).entrySet();
        adByTableArray = set.toArray(new Map.Entry[set.size()]);
    }

}
