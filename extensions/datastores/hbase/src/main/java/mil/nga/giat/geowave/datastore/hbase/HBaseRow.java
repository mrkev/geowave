package mil.nga.giat.geowave.datastore.hbase;

import java.util.List;

import mil.nga.giat.geowave.core.store.base.DataStoreEntryInfo.FieldInfo;
import mil.nga.giat.geowave.core.store.entities.GeoWaveRowImpl;

public class HBaseRow extends
		GeoWaveRowImpl
{
	private List<FieldInfo<?>> fieldInfoList;

	public HBaseRow(
			byte[] rowId,
			List<FieldInfo<?>> fieldInfoList ) {
		super(
				rowId);
		this.fieldInfoList = fieldInfoList;
	}

	public List<FieldInfo<?>> getFieldInfoList() {
		return fieldInfoList;
	}
}
