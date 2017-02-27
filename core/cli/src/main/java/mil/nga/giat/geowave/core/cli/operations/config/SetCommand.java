package mil.nga.giat.geowave.core.cli.operations.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.restlet.resource.Get;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;

import mil.nga.giat.geowave.core.cli.annotations.GeowaveOperation;
import mil.nga.giat.geowave.core.cli.api.Command;
import mil.nga.giat.geowave.core.cli.api.DefaultOperation;
import mil.nga.giat.geowave.core.cli.api.OperationParams;
import mil.nga.giat.geowave.core.cli.operations.config.options.ConfigOptions;
import mil.nga.giat.geowave.core.cli.parser.ManualOperationParams;

@GeowaveOperation(name = "set", parentOperation = ConfigSection.class)
@Parameters(commandDescription = "Set property name within cache")
public class SetCommand extends
		DefaultOperation implements
		Command
{

	private static int SUCCESS = 0;
	private static int USAGE_ERROR = -1;
	private static int WRITE_FAILURE = -2;

	@Parameter(description = "<name> <value>")
	private List<String> parameters = new ArrayList<String>();

	@Override
	public void execute(
			OperationParams params ) {
		Result result = setKeyValue(params);
		if (result.result == USAGE_ERROR) {
			throw new ParameterException(
					"Requires: <name> <value>");
		}
	}

	/**
	 * Add rest endpoint for the set command. Looks for GET params with keys
	 * 'key' and 'value' to set.
	 * 
	 * @return string containing json with details of success or failure of the
	 *         set
	 */
	@Get("json")
	public String computeResults() {
		String key = getQueryValue("key");
		String value = getQueryValue("value");

		if ((key == null || key.equals("")) || value == null) {
			return "{ \"result\":" + USAGE_ERROR + ", \"message\":\"requires get params key,value\",\"prev\":\"\"}";
		}

		setParameters(
				key,
				value);
		OperationParams params = new ManualOperationParams();
		// TODO just adding this file information causes the config file to
		// be stored as 'unknownversion-config.properties' which probably
		// should change..
		params.getContext().put(
				ConfigOptions.PROPERTIES_FILE_CONTEXT,
				ConfigOptions.getDefaultPropertyFile());

		Result result = setKeyValue(params);

		if (result.result == WRITE_FAILURE) {
			return "{ \"result\":" + WRITE_FAILURE + ", \"message\":\"write failure\",\"prev\":\""
					+ result.previousValue + "\"}";
		}
		else {
			return "{ \"result\":" + SUCCESS + ", \"message\":\"\",\"prev\":\"" + result.previousValue + "\"}";
		}

	}

	/**
	 * Set the key value pair in the config. Store the previous value of the key
	 * in prevValue
	 */
	private Result setKeyValue(
			OperationParams params ) {
		Result result = new Result();

		File f = (File) params.getContext().get(
				ConfigOptions.PROPERTIES_FILE_CONTEXT);
		Properties p = ConfigOptions.loadProperties(
				f,
				null);

		String key = null;
		String value = null;
		if (parameters.size() == 1 && parameters.get(
				0).indexOf(
				"=") != -1) {
			String[] parts = StringUtils.split(
					parameters.get(0),
					"=");
			key = parts[0];
			value = parts[1];
		}
		else if (parameters.size() == 2) {
			key = parameters.get(0);
			value = parameters.get(1);
		}
		else {
			result.result = USAGE_ERROR;
			return result;

		}

		result.previousValue = p.setProperty(
				key,
				value);
		if (!ConfigOptions.writeProperties(
				f,
				p)) {
			result.result = WRITE_FAILURE;
			return result;
		}
		else {
			result.result = SUCCESS;
			return result;
		}
	}

	public List<String> getParameters() {
		return parameters;
	}

	public void setParameters(
			String key,
			String value ) {
		this.parameters = new ArrayList<String>();
		this.parameters.add(key);
		this.parameters.add(value);
	}

	private static class Result
	{
		int result;
		Object previousValue;
	}
}
