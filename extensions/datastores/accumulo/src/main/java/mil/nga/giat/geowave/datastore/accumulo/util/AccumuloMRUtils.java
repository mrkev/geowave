package mil.nga.giat.geowave.datastore.accumulo.util;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import mil.nga.giat.geowave.core.index.NumericIndexStrategy;
import mil.nga.giat.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import mil.nga.giat.geowave.core.store.index.Index;
import mil.nga.giat.geowave.core.store.query.DistributableQuery;
import mil.nga.giat.geowave.mapreduce.input.GeoWaveInputSplit;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.TableDeletedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.TableOfflineException;
import org.apache.accumulo.core.client.impl.TabletLocator;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.master.state.tables.TableState;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.security.Credentials;

import com.google.common.collect.Tables;

public class AccumuloMRUtils
{

	/**
	 * Read the metadata table to get tablets and match up ranges to them.
	 */
	public static List<InputSplit> getSplits(
			final JobContext context )
			throws IOException,
			InterruptedException {
		final Integer minSplits = GeoWaveInputFormat.getMinimumSplitCount(context);
		final Integer maxSplits = getMaximumSplitCount(context);
		final TreeSet<IntermediateSplitInfo> splits = getIntermediateSplits(
				context,
				maxSplits);
		// this is an incremental algorithm, it may be better use the target
		// split count to drive it (ie. to get 3 splits this will split 1 large
		// range into two down the middle and then split one of those ranges
		// down the middle to get 3, rather than splitting one range into
		// thirds)
		if ((minSplits != null) && (splits.size() < minSplits)) {
			// set the ranges to at least min splits
			do {
				// remove the highest range, split it into 2 and add both back,
				// increasing the size by 1
				final IntermediateSplitInfo highestSplit = splits.pollLast();
				final IntermediateSplitInfo otherSplit = highestSplit.split();
				splits.add(highestSplit);
				splits.add(otherSplit);
			}
			while (splits.size() < minSplits);
		}
		else if (((maxSplits != null) && (maxSplits > 0)) && (splits.size() > maxSplits)) {
			// merge splits to fit within max splits
			do {
				// this is the naive approach, remove the lowest two ranges and
				// merge them, decreasing the size by 1

				// TODO Ideally merge takes into account locations (as well as
				// possibly the index as a secondary criteria) to limit the
				// number of locations/indices
				final IntermediateSplitInfo lowestSplit = splits.pollFirst();
				final IntermediateSplitInfo nextLowestSplit = splits.pollFirst();
				lowestSplit.merge(nextLowestSplit);
				splits.add(lowestSplit);
			}
			while (splits.size() > maxSplits);
		}
		final List<InputSplit> retVal = new ArrayList<InputSplit>();
		for (final IntermediateSplitInfo split : splits) {
			retVal.add(split.toFinalSplit());
		}

		return retVal;
	}

	private static TreeSet<IntermediateSplitInfo> getIntermediateSplits(
			final JobContext context,
			final Integer maxSplits )
			throws IOException {
		final Index[] indices = getIndices(context);
		final DistributableQuery query = getQuery(context);
		final String tableNamespace = getTableNamespace(context);

		final TreeSet<IntermediateSplitInfo> splits = new TreeSet<IntermediateSplitInfo>();
		for (final Index index : indices) {
			if ((query != null) && !query.isSupported(index)) {
				continue;
			}
			final String tableName = AccumuloUtils.getQualifiedTableName(
					tableNamespace,
					index.getId().getString());
			final NumericIndexStrategy indexStrategy = index.getIndexStrategy();
			final TreeSet<Range> ranges;
			if (query != null) {
				final MultiDimensionalNumericData indexConstraints = query.getIndexConstraints(indexStrategy);
				if ((maxSplits != null) && (maxSplits > 0)) {
					ranges = AccumuloUtils.byteArrayRangesToAccumuloRanges(AccumuloUtils.constraintsToByteArrayRanges(
							indexConstraints,
							indexStrategy,
							maxSplits));
				}
				else {
					ranges = AccumuloUtils.byteArrayRangesToAccumuloRanges(AccumuloUtils.constraintsToByteArrayRanges(
							indexConstraints,
							indexStrategy));
				}
			}
			else {
				ranges = new TreeSet<Range>();
				ranges.add(new Range());
			}
			// get the metadata information for these ranges
			final Map<String, Map<KeyExtent, List<Range>>> tserverBinnedRanges = new HashMap<String, Map<KeyExtent, List<Range>>>();
			TabletLocator tl;
			try {
				final Instance instance = getInstance(context);
				final String tableId = Tables.getTableId(
						instance,
						tableName);
				tl = getTabletLocator(
						instance,
						tableName,
						tableId);
				// its possible that the cache could contain complete, but
				// old information about a tables tablets... so clear it
				tl.invalidateCache();
				final String instanceId = instance.getInstanceID();
				final List<Range> rangeList = new ArrayList<Range>(
						ranges);
				final Random r = new Random();
				while (!binRanges(
						rangeList,
						getUserName(context),
						getPassword(context),
						tserverBinnedRanges,
						tl,
						instanceId)) {
					if (!(instance instanceof MockInstance)) {
						if (!Tables.exists(
								instance,
								tableId)) {
							throw new TableDeletedException(
									tableId);
						}
						if (Tables.getTableState(
								instance,
								tableId) == TableState.OFFLINE) {
							throw new TableOfflineException(
									instance,
									tableId);
						}
					}
					tserverBinnedRanges.clear();
					LOGGER.warn("Unable to locate bins for specified ranges. Retrying.");
					UtilWaitThread.sleep(100 + r.nextInt(101));
					// sleep randomly between 100 and 200 ms
					tl.invalidateCache();
				}
			}
			catch (final Exception e) {
				throw new IOException(
						e);
			}
			final HashMap<String, String> hostNameCache = new HashMap<String, String>();
			for (final Entry<String, Map<KeyExtent, List<Range>>> tserverBin : tserverBinnedRanges.entrySet()) {
				final String tabletServer = tserverBin.getKey();
				final String ipAddress = tabletServer.split(
						":",
						2)[0];

				String location = hostNameCache.get(ipAddress);
				if (location == null) {
					final InetAddress inetAddress = InetAddress.getByName(ipAddress);
					location = inetAddress.getHostName();
					hostNameCache.put(
							ipAddress,
							location);
				}
				for (final Entry<KeyExtent, List<Range>> extentRanges : tserverBin.getValue().entrySet()) {
					final Range keyExtent = extentRanges.getKey().toDataRange();
					final Map<Index, List<RangeLocationPair>> splitInfo = new HashMap<Index, List<RangeLocationPair>>();
					final List<RangeLocationPair> rangeList = new ArrayList<RangeLocationPair>();
					for (final Range range : extentRanges.getValue()) {
						rangeList.add(new RangeLocationPair(
								keyExtent.clip(range),
								location));
					}
					splitInfo.put(
							index,
							rangeList);
					splits.add(new IntermediateSplitInfo(
							splitInfo));
				}
			}
		}
		return splits;
	}

	protected static class IntermediateSplitInfo implements
			Comparable<IntermediateSplitInfo>
	{
		protected static class IndexRangeLocation
		{
			private final RangeLocationPair rangeLocationPair;
			private final Index index;

			public IndexRangeLocation(
					final RangeLocationPair rangeLocationPair,
					final Index index ) {
				this.rangeLocationPair = rangeLocationPair;
				this.index = index;
			}
		}

		protected static class RangeLocationPair
		{
			private final Range range;
			private final String location;
			private final Map<Integer, BigInteger> rangePerCardinalityCache = new HashMap<Integer, BigInteger>();

			public RangeLocationPair(
					final Range range,
					final String location ) {
				this.location = location;
				this.range = range;
			}

			protected BigInteger getRangeAtCardinality(
					final int cardinality ) {
				final BigInteger rangeAtCardinality = rangePerCardinalityCache.get(cardinality);
				if (rangeAtCardinality != null) {
					return rangeAtCardinality;
				}
				return calcRange(cardinality);

			}

			private BigInteger calcRange(
					final int cardinality ) {
				final BigInteger r = getRange(
						range,
						cardinality);
				rangePerCardinalityCache.put(
						cardinality,
						r);
				return r;
			}
		}

		private final Map<Index, List<RangeLocationPair>> splitInfo;
		private final Map<Integer, BigInteger> totalRangePerCardinalityCache = new HashMap<Integer, BigInteger>();

		public IntermediateSplitInfo(
				final Map<Index, List<RangeLocationPair>> splitInfo ) {
			this.splitInfo = splitInfo;
		}

		private synchronized void merge(
				final IntermediateSplitInfo split ) {
			clearCache();
			for (final Entry<Index, List<RangeLocationPair>> e : split.splitInfo.entrySet()) {
				List<RangeLocationPair> thisList = splitInfo.get(e.getKey());
				if (thisList == null) {
					thisList = new ArrayList<RangeLocationPair>();
					splitInfo.put(
							e.getKey(),
							thisList);
				}
				thisList.addAll(e.getValue());
			}
		}

		private synchronized IntermediateSplitInfo split() {
			final int maxCardinality = getMaxCardinality();
			final BigInteger totalRange = getTotalRangeAtCardinality(maxCardinality);

			// generically you'd want the split to be as limiting to total
			// locations as possible and then as limiting as possible to total
			// indices, but in this case split() is only called when all ranges
			// are in the same location and the same index

			// and you want it to split the ranges into two by total range
			final TreeSet<IndexRangeLocation> orderedSplits = new TreeSet<IndexRangeLocation>(
					new Comparator<IndexRangeLocation>() {

						@Override
						public int compare(
								final IndexRangeLocation o1,
								final IndexRangeLocation o2 ) {
							final BigInteger range1 = o1.rangeLocationPair.getRangeAtCardinality(maxCardinality);
							final BigInteger range2 = o2.rangeLocationPair.getRangeAtCardinality(maxCardinality);
							int retVal = range1.compareTo(range2);
							if (retVal == 0) {
								// we really want to avoid equality because
								retVal = Long.compare(
										o1.hashCode(),
										o2.hashCode());
								if (retVal == 0) {
									// what the heck, give it one last insurance
									// that they're not equal even though its
									// extremely unlikely
									retVal = Long.compare(
											o1.rangeLocationPair.rangePerCardinalityCache.hashCode(),
											o2.rangeLocationPair.rangePerCardinalityCache.hashCode());
								}
							}
							return retVal;
						}
					});
			for (final Entry<Index, List<RangeLocationPair>> ranges : splitInfo.entrySet()) {
				for (final RangeLocationPair p : ranges.getValue()) {
					orderedSplits.add(new IndexRangeLocation(
							p,
							ranges.getKey()));
				}
			}
			IndexRangeLocation pairToSplit;
			BigInteger targetRange = totalRange.divide(TWO);
			final Map<Index, List<RangeLocationPair>> otherSplitInfo = new HashMap<Index, List<RangeLocationPair>>();
			do {
				// this will get the least value at or above the target range
				final BigInteger compareRange = targetRange;
				pairToSplit = orderedSplits.ceiling(new IndexRangeLocation(
						new RangeLocationPair(
								null,
								null) {

							@Override
							protected BigInteger getRangeAtCardinality(
									final int cardinality ) {
								return compareRange;
							}

						},
						null));
				// there are no elements greater than the target, so take the
				// largest element and adjust the target
				if (pairToSplit == null) {
					final IndexRangeLocation highestRange = orderedSplits.pollLast();
					List<RangeLocationPair> rangeList = otherSplitInfo.get(highestRange.index);
					if (rangeList == null) {
						rangeList = new ArrayList<RangeLocationPair>();
						otherSplitInfo.put(
								highestRange.index,
								rangeList);
					}
					rangeList.add(highestRange.rangeLocationPair);
					targetRange = targetRange.subtract(highestRange.rangeLocationPair.getRangeAtCardinality(maxCardinality));
				}
			}
			while ((pairToSplit == null) && !orderedSplits.isEmpty());

			if (pairToSplit == null) {
				// this should never happen!
				LOGGER.error("Unable to identify splits");
				// but if it does, just take the first range off of this and
				// split it in half if this is left as empty
				clearCache();
				return splitSingleRange(maxCardinality);
			}

			// now we just carve the pair to split by the amount we are over
			// the target range
			final BigInteger currentRange = pairToSplit.rangeLocationPair.getRangeAtCardinality(maxCardinality);
			final BigInteger rangeExceeded = currentRange.subtract(targetRange);
			if (rangeExceeded.compareTo(BigInteger.ZERO) > 0) {
				// remove pair to split from ordered splits and split it to
				// attempt to match the target range, adding the appropriate
				// sides of the range to this info's ordered splits and the
				// other's splits
				orderedSplits.remove(pairToSplit);
				final BigInteger end = getEnd(
						pairToSplit.rangeLocationPair.range,
						maxCardinality);
				final byte[] splitKey = getKeyFromBigInteger(
						end.subtract(rangeExceeded),
						maxCardinality);
				List<RangeLocationPair> rangeList = otherSplitInfo.get(pairToSplit.index);
				if (rangeList == null) {
					rangeList = new ArrayList<RangeLocationPair>();
					otherSplitInfo.put(
							pairToSplit.index,
							rangeList);
				}
				rangeList.add(new RangeLocationPair(
						new Range(
								pairToSplit.rangeLocationPair.range.getStartKey(),
								pairToSplit.rangeLocationPair.range.isStartKeyInclusive(),
								new Key(
										new Text(
												splitKey)),
								false),
						pairToSplit.rangeLocationPair.location));
				orderedSplits.add(new IndexRangeLocation(
						new RangeLocationPair(
								new Range(
										new Key(
												new Text(
														splitKey)),
										true,
										pairToSplit.rangeLocationPair.range.getEndKey(),
										pairToSplit.rangeLocationPair.range.isEndKeyInclusive()),
								pairToSplit.rangeLocationPair.location),
						pairToSplit.index));
			}
			else if (orderedSplits.size() > 1) {
				// add pair to split to other split and remove it from
				// orderedSplits
				orderedSplits.remove(pairToSplit);
				List<RangeLocationPair> rangeList = otherSplitInfo.get(pairToSplit.index);
				if (rangeList == null) {
					rangeList = new ArrayList<RangeLocationPair>();
					otherSplitInfo.put(
							pairToSplit.index,
							rangeList);
				}
				rangeList.add(pairToSplit.rangeLocationPair);
			}

			// clear splitinfo and set it to ordered splits (what is left of the
			// splits that haven't been placed in the other split info)
			splitInfo.clear();
			for (final IndexRangeLocation split : orderedSplits) {
				List<RangeLocationPair> rangeList = splitInfo.get(split.index);
				if (rangeList == null) {
					rangeList = new ArrayList<RangeLocationPair>();
					splitInfo.put(
							split.index,
							rangeList);
				}
				rangeList.add(split.rangeLocationPair);
			}
			clearCache();
			return new IntermediateSplitInfo(
					otherSplitInfo);
		}

		private IntermediateSplitInfo splitSingleRange(
				final int maxCardinality ) {
			final Map<Index, List<RangeLocationPair>> otherSplitInfo = new HashMap<Index, List<RangeLocationPair>>();
			final List<RangeLocationPair> otherRangeList = new ArrayList<RangeLocationPair>();
			final Iterator<Entry<Index, List<RangeLocationPair>>> it = splitInfo.entrySet().iterator();
			while (it.hasNext()) {
				final Entry<Index, List<RangeLocationPair>> e = it.next();
				final List<RangeLocationPair> rangeList = e.getValue();
				if (!rangeList.isEmpty()) {
					final RangeLocationPair p = rangeList.remove(0);
					if (rangeList.isEmpty()) {
						if (!it.hasNext()) {
							// if this is empty now, divide the split in
							// half
							final BigInteger range = p.getRangeAtCardinality(maxCardinality);
							final BigInteger start = getStart(
									p.range,
									maxCardinality);
							final byte[] splitKey = getKeyFromBigInteger(
									start.add(range.divide(TWO)),
									maxCardinality);
							rangeList.add(new RangeLocationPair(
									new Range(
											p.range.getStartKey(),
											p.range.isStartKeyInclusive(),
											new Key(
													new Text(
															splitKey)),
											false),
									p.location));
							otherRangeList.add(new RangeLocationPair(
									new Range(
											new Key(
													new Text(
															splitKey)),
											true,
											p.range.getEndKey(),
											p.range.isEndKeyInclusive()),
									p.location));
							otherSplitInfo.put(
									e.getKey(),
									otherRangeList);
							return new IntermediateSplitInfo(
									otherSplitInfo);
						}
						else {
							// otherwise remove this entry
							it.remove();
						}
					}
					otherRangeList.add(p);
					otherSplitInfo.put(
							e.getKey(),
							otherRangeList);
					return new IntermediateSplitInfo(
							otherSplitInfo);
				}
			}
			// this can only mean there are no ranges
			LOGGER.error("Attempting to split ranges on empty range");
			return new IntermediateSplitInfo(
					otherSplitInfo);
		}

		private synchronized GeoWaveInputSplit toFinalSplit() {
			final Map<Index, List<Range>> rangesPerIndex = new HashMap<Index, List<Range>>();
			final Set<String> locations = new HashSet<String>();
			for (final Entry<Index, List<RangeLocationPair>> entry : splitInfo.entrySet()) {
				final List<Range> ranges = new ArrayList<Range>(
						entry.getValue().size());
				for (final RangeLocationPair pair : entry.getValue()) {
					locations.add(pair.location);
					ranges.add(pair.range);
				}
				rangesPerIndex.put(
						entry.getKey(),
						ranges);
			}
			return new GeoWaveInputSplit(
					rangesPerIndex,
					locations.toArray(new String[locations.size()]));
		}

		private synchronized int getMaxCardinality() {
			int maxCardinality = 1;
			for (final List<RangeLocationPair> pList : splitInfo.values()) {
				for (final RangeLocationPair p : pList) {
					maxCardinality = Math.max(
							maxCardinality,
							getMaxCardinalityFromRange(p.range));
				}
			}
			return maxCardinality;
		}

		@Override
		public int compareTo(
				final IntermediateSplitInfo o ) {
			final int maxCardinality = Math.max(
					getMaxCardinality(),
					o.getMaxCardinality());
			final BigInteger thisTotal = getTotalRangeAtCardinality(maxCardinality);
			final BigInteger otherTotal = o.getTotalRangeAtCardinality(maxCardinality);
			int retVal = thisTotal.compareTo(otherTotal);
			if (retVal == 0) {
				// because this is used by the treeset, we really want to avoid
				// equality
				retVal = Long.compare(
						hashCode(),
						o.hashCode());
				// what the heck, give it one last insurance
				// that they're not equal even though its
				// extremely unlikely
				if (retVal == 0) {
					retVal = Long.compare(
							totalRangePerCardinalityCache.hashCode(),
							o.totalRangePerCardinalityCache.hashCode());
				}
			}
			return retVal;
		}

		@Override
		public boolean equals(
				final Object obj ) {
			if (obj == null) {
				return false;
			}
			if (!(obj instanceof IntermediateSplitInfo)) {
				return false;
			}
			return compareTo((IntermediateSplitInfo) obj) == 0;
		}

		@Override
		public int hashCode() {
			// think this matches the spirit of compareTo
			final int mc = getMaxCardinality();
			return com.google.common.base.Objects.hashCode(
					mc,
					getTotalRangeAtCardinality(mc),
					super.hashCode());
		}

		private synchronized BigInteger getTotalRangeAtCardinality(
				final int cardinality ) {
			final BigInteger totalRange = totalRangePerCardinalityCache.get(cardinality);
			if (totalRange != null) {
				return totalRange;
			}
			return calculateTotalRangeForCardinality(cardinality);
		}

		private synchronized BigInteger calculateTotalRangeForCardinality(
				final int cardinality ) {
			BigInteger sum = BigInteger.ZERO;
			for (final List<RangeLocationPair> pairList : splitInfo.values()) {
				for (final RangeLocationPair pair : pairList) {
					sum = sum.add(pair.getRangeAtCardinality(cardinality));
				}
			}
			totalRangePerCardinalityCache.put(
					cardinality,
					sum);
			return sum;
		}

		private synchronized void clearCache() {
			totalRangePerCardinalityCache.clear();
		}
	}

	protected static int getMaxCardinalityFromRange(
			final Range range ) {
		int maxCardinality = 0;
		final Key start = range.getStartKey();
		if (start != null) {
			maxCardinality = Math.max(
					maxCardinality,
					start.getRowData().length());
		}
		final Key end = range.getEndKey();
		if (end != null) {
			maxCardinality = Math.max(
					maxCardinality,
					end.getRowData().length());
		}
		return maxCardinality;
	}

	protected static byte[] getKeyFromBigInteger(
			final BigInteger value,
			final int numBytes ) {
		final byte[] valueBytes = value.toByteArray();
		final byte[] bytes = new byte[numBytes];
		for (int i = 0; i < numBytes; i++) {
			// start from the right
			if (i < valueBytes.length) {
				bytes[bytes.length - i - 1] = valueBytes[valueBytes.length - i - 1];
			}
			else {
				// prepend anything outside of the BigInteger value with 0
				bytes[bytes.length - i - 1] = 0;
			}
		}
		return bytes;
	}

	protected static byte[] extractBytes(
			final ByteSequence seq,
			final int numBytes ) {
		return extractBytes(
				seq,
				numBytes,
				false);
	}

	protected static byte[] extractBytes(
			final ByteSequence seq,
			final int numBytes,
			final boolean infiniteEndKey ) {
		final byte[] bytes = new byte[numBytes + 2];
		bytes[0] = 1;
		bytes[1] = 0;
		for (int i = 0; i < numBytes; i++) {
			if (i >= seq.length()) {
				if (infiniteEndKey) {
					// -1 is 0xff
					bytes[i + 2] = -1;
				}
				else {
					bytes[i + 2] = 0;
				}
			}
			else {
				bytes[i + 2] = seq.byteAt(i);
			}
		}
		return bytes;
	}

	protected static BigInteger getRange(
			final Range range,
			final int cardinality ) {
		return getEnd(
				range,
				cardinality).subtract(
				getStart(
						range,
						cardinality));
	}

	protected static BigInteger getStart(
			final Range range,
			final int cardinality ) {
		final Key start = range.getStartKey();
		byte[] startBytes;
		if (!range.isInfiniteStartKey() && (start != null)) {
			startBytes = extractBytes(
					start.getRowData(),
					cardinality);
		}
		else {
			startBytes = extractBytes(
					new ArrayByteSequence(
							new byte[] {}),
					cardinality);
		}
		return new BigInteger(
				startBytes);
	}

	/**
	 * Initializes an Accumulo {@link TabletLocator} based on the configuration.
	 *
	 * @param instance
	 *            the accumulo instance
	 * @param tableName
	 *            the accumulo table name
	 * @return an Accumulo tablet locator
	 * @throws TableNotFoundException
	 *             if the table name set on the configuration doesn't exist
	 * @since 1.5.0
	 */
	protected static TabletLocator getTabletLocator(
			final Instance instance,
			final String tableName,
			final String tableId )
			throws TableNotFoundException {
		TabletLocator tabletLocator;
		// @formatter:off
		/*if[ACCUMULO_1.5.2]
		tabletLocator = TabletLocator.getInstance(
				instance,
				new Text(
						Tables.getTableId(
								instance,
								tableName)));

  		else[ACCUMULO_1.5.2]*/
		tabletLocator = TabletLocator.getLocator(
				instance,
				new Text(
						tableId));
		/*end[ACCUMULO_1.5.2]*/
		// @formatter:on
		return tabletLocator;
	}

	protected static boolean binRanges(
			final List<Range> rangeList,
			final String userName,
			final String password,
			final Map<String, Map<KeyExtent, List<Range>>> tserverBinnedRanges,
			final TabletLocator tabletLocator,
			final String instanceId )
			throws AccumuloException,
			AccumuloSecurityException,
			TableNotFoundException,
			IOException {
		// @formatter:off
		/*if[ACCUMULO_1.5.2]
		final ByteArrayOutputStream backingByteArray = new ByteArrayOutputStream();
		final DataOutputStream output = new DataOutputStream(
				backingByteArray);
		new PasswordToken(
				password).write(output);
		output.close();
		final ByteBuffer buffer = ByteBuffer.wrap(backingByteArray.toByteArray());
		final TCredentials credentials = new TCredentials(
				userName,
				PasswordToken.class.getCanonicalName(),
				buffer,
				instanceId);
		return tabletLocator.binRanges(
				rangeList,
				tserverBinnedRanges,
				credentials).isEmpty();
  		else[ACCUMULO_1.5.2]*/
		return tabletLocator.binRanges(
				new Credentials(
						userName,
						new PasswordToken(
								password)),
				rangeList,
				tserverBinnedRanges).isEmpty();
  		/*end[ACCUMULO_1.5.2]*/
		// @formatter:on
	}

	protected static BigInteger getEnd(
			final Range range,
			final int cardinality ) {
		final Key end = range.getEndKey();
		byte[] endBytes;
		if (!range.isInfiniteStopKey() && (end != null)) {
			endBytes = extractBytes(
					end.getRowData(),
					cardinality);
		}
		else {
			endBytes = extractBytes(
					new ArrayByteSequence(
							new byte[] {}),
					cardinality,
					true);
		}

		return new BigInteger(
				endBytes);
	}
}