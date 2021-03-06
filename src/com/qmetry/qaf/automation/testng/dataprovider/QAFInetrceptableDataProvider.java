/*******************************************************************************
 * QMetry Automation Framework provides a powerful and versatile platform to author 
 * Automated Test Cases in Behavior Driven, Keyword Driven or Code Driven approach
 *                
 * Copyright 2016 Infostretch Corporation
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
 * OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE
 *
 * You should have received a copy of the GNU General Public License along with this program in the name of LICENSE.txt in the root folder of the distribution. If not, see https://opensource.org/licenses/gpl-3.0.html
 *
 * See the NOTICE.TXT file in root folder of this source files distribution 
 * for additional information regarding copyright ownership and licenses
 * of other open source software / files used by QMetry Automation Framework.
 *
 * For any inquiry or need additional information, please contact support-qaf@infostretch.com
 *******************************************************************************/

package com.qmetry.qaf.automation.testng.dataprovider;

import static com.qmetry.qaf.automation.core.ConfigurationManager.getBundle;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.impl.LogFactoryImpl;
import org.json.JSONObject;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.annotations.DataProvider;
import org.testng.internal.ClassHelper;
import org.testng.internal.Configuration;
import org.testng.internal.MethodInvocationHelper;
import org.testng.internal.annotations.IAnnotationFinder;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.qmetry.qaf.automation.core.ConfigurationManager;
import com.qmetry.qaf.automation.data.DataBean;
import com.qmetry.qaf.automation.keys.ApplicationProperties;
import com.qmetry.qaf.automation.step.client.TestNGScenario;
import com.qmetry.qaf.automation.testng.DataProviderException;
import com.qmetry.qaf.automation.testng.dataprovider.QAFDataProvider.params;
import com.qmetry.qaf.automation.util.CSVUtil;
import com.qmetry.qaf.automation.util.ClassUtil;
import com.qmetry.qaf.automation.util.DatabaseUtil;
import com.qmetry.qaf.automation.util.ExcelUtil;
import com.qmetry.qaf.automation.util.JSONUtil;
import com.qmetry.qaf.automation.util.ListUtils;
import com.qmetry.qaf.automation.util.StringUtil;

/**
 * @author Chirag.Jayswal
 *
 */
public class QAFInetrceptableDataProvider {
	private static final Log logger = LogFactoryImpl.getLog(QAFInetrceptableDataProvider.class);

	/**
	 * 
	 * @param method
	 * @param c
	 * @return
	 */
	@DataProvider(name = QAFDataProvider.NAME_PARALLEL, parallel = true)
	public static Iterator<Object[]> interceptedParallelDataProvider(ITestNGMethod method, ITestContext c) {
		return interceptedDataProvider(method, c);
	}

	/**
	 * 
	 * @param method
	 * @param c
	 * @return
	 */
	@DataProvider(name = QAFDataProvider.NAME)
	public static Iterator<Object[]> interceptedDataProvider(ITestNGMethod method, ITestContext c) {
		TestNGScenario scenario = (TestNGScenario) method;
		@SuppressWarnings("unchecked")
		Map<String, Object> parameters = (Map<String, Object>) getParameters(scenario);
		// update resolved meta-data should reflect in report
		Map<String, Object> metadata = scenario.getMetaData();
		metadata.putAll(parameters);
		
		// Intercepter registered using property 'qaf.listeners'
		Set<QAFDataProviderIntercepter> intercepters = getIntercepters();
		for (QAFDataProviderIntercepter intercepter : intercepters) {
			intercepter.beforeFech(scenario, c);
		}
				
		List<Object[]> dataList = null;
		String dataProvider = (String) metadata.get(params.DATAPROVIDER.name());
		boolean hasCustomDataProvider = null!=dataProvider && !dataProvider.startsWith(QAFDataProvider.NAME);
		
		if (hasCustomDataProvider) {
			// get data provider from description!...
			String dataProviderClass = (String) metadata.get(params.DATAPROVIDERCLASS.name());
			Iterator<Object[]> testData = invokeCustomDataProvider(method, c, dataProvider, dataProviderClass);
			dataList = ListUtils.toList(testData);
		} else {
			Object[][] testData = getData(metadata);
			dataList = ListUtils.toList(testData);
		}

		List<Object[]> data = process(scenario, dataList);

		// listeners
		List<Object[]> interceptedData = intercept(scenario, c, data,intercepters);
		return interceptedData.iterator();
	}

	private static List<Object[]> intercept(TestNGScenario scenario, ITestContext context, List<Object[]> testdata, Set<QAFDataProviderIntercepter> intercepters) {
		

		// Intercepter registered using property 'qaf.listeners'
		for (QAFDataProviderIntercepter intercepter : intercepters) {
			testdata = intercepter.intercept(scenario, context, testdata);
		}
		int from = 0;
		int to = testdata.size() - 1;
		
		Map<String, Object> metadata = scenario.getMetaData();
		if (metadata.containsKey(params.FROM.name()) || metadata.containsKey(params.TO.name())) {
			if (metadata.containsKey(params.TO.name()) && (int) metadata.get(params.TO.name()) < to) {
				to = (int) metadata.get(params.TO.name());
			}
			if (metadata.containsKey(params.FROM.name())) {
				from = (int) metadata.get(params.FROM.name());
			}
			return testdata.subList(from, to);
		}

		if (metadata.containsKey(params.INDICES.name())) {
			List<?> indices = (List<?>) metadata.get(params.INDICES.name());
			List<Object[]> filteredList = new ArrayList<Object[]>();
			for (Object i : indices) {
				filteredList.add(testdata.get((int) i));
			}
			return filteredList;
		}
		return testdata;
	}

	private static Map<?, ?> getParameters(TestNGScenario scenario) {
		Map<String, Object> methodParameters = scenario.getMetaData();
		String description = scenario.getDescription();
		if (isNotBlank(description) && JSONUtil.isValidJsonString(description)) {
			Map<String, Object> paramsFromDesc = new JSONObject(description).toMap();
			description =(String) paramsFromDesc.remove("description");
			methodParameters.putAll(paramsFromDesc);
			scenario.setDescription(description);
		}

		// highest priority test data overridden through property with test name
		// prefix
		String testParameters = getBundle().getString(scenario.getMethodName() + ".testdata");
		if (isBlank(testParameters)) {
			boolean hasDataProvider = false;
			for (params param : params.values()) {
				if (methodParameters.containsKey(param.name())) {
					hasDataProvider = true;
					break;
				}
			}
			if (hasDataProvider) {
				testParameters = new JSONObject(methodParameters).toString();
			} else {
				// lowest priority to global test data
				testParameters = getBundle().getString("global.testdata");
			}
		}
		String cls = scenario.getMethod().getDeclaringClass().getSimpleName();
		String mtd = scenario.getMethodName();
		testParameters = testParameters.replace("${class}", cls);
		testParameters = testParameters.replace("${method}", mtd);
		testParameters = StrSubstitutor.replace(testParameters, methodParameters);
		testParameters = getBundle().getSubstitutor().replace(testParameters);
		try {
			return new JSONObject(testParameters).toMap();
		} catch (JsonSyntaxException e) {
			// old way of setting global data or testdata using key=value
			return StringUtil.toMap(testParameters, true);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Object[]> process(TestNGScenario scenario, List<Object[]> testdata) {
		Class<?>[] paramTypes = scenario.getConstructorOrMethod().getParameterTypes();

		// list of only map object
		if (null != testdata && !testdata.isEmpty() && testdata.get(0).length == 1
				&& Map.class.isAssignableFrom(testdata.get(0)[0].getClass())) {

			try {
				// filter records using key include/exclude if provided in data
				String filter = (String) scenario.getMetaData().get("filter");
				if (StringUtil.isNotBlank(filter)) {
					// resolve parameters
					TreeMap<String, Object> parametes = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
					parametes.putAll(scenario.getMetaData());
					parametes.put("method", scenario.getMethodName());
					parametes.put("class", scenario.getMethod().getDeclaringClass().getSimpleName());
					
					filter = StrSubstitutor.replace(filter, parametes);
					filter = getBundle().getSubstitutor().replace(filter);
					logger.info("Applying Filter " + filter);

					Iterator<Object[]> iter = testdata.iterator();
					while (iter.hasNext()) {
						// consider column values as context variables
						Map<String, Object> record = (Map<String, Object>) iter.next()[0];
						boolean include = StringUtil.eval(filter, record);
						if (!include) {
							logger.debug("removing " + record);
							iter.remove();
						}
					}
				}
			} catch (Exception e) {
				throw new DataProviderException("Unable to apply filter on data-set", e);
			}

			// process parameters
			for (int i = 0; i < testdata.size(); i++) {
				Map<String, Object> record = (Map<String, Object>) testdata.get(i)[0];
				if (paramTypes.length > 1) {
					Object[] values = record.values().toArray();
					if (paramTypes.length == values.length) {
						testdata.set(i, values);
					} else {
						Object[] params = new Object[paramTypes.length];
						for (int pi = 0; pi < paramTypes.length; pi++) {
							try {
								if (values.length > pi && paramTypes[pi].isAssignableFrom(values[pi].getClass())) {
									params[pi] = values[pi];
								} else if (DataBean.class.isAssignableFrom(paramTypes[pi])) {
									DataBean bean = (DataBean) paramTypes[pi].newInstance();
									bean.fillData(record);
									params[pi] = bean;

								} else {
									Object bean = paramTypes[pi].newInstance();
									BeanUtils.populate(bean, record);
								}
							} catch (Exception e) {
								throw new DataProviderException("Unable to populate data" + paramTypes, e);
							}
						}
						testdata.set(i, params);
					}
				} else if (!Map.class.isAssignableFrom(paramTypes[0])) {
					if (DataBean.class.isAssignableFrom(paramTypes[0])) {
						try {
							DataBean bean = (DataBean) paramTypes[0].newInstance();
							bean.fillData(record);
							testdata.set(i, new Object[] { bean });

						} catch (Exception e) {
							throw new DataProviderException("Unable to populate databean", e);
						}
					} else {
						Gson gson = new Gson();
						String seralizedObj = gson.toJson(record);
						Object obj = new Gson().fromJson(seralizedObj, paramTypes[0]);
						testdata.set(i, new Object[] { obj });
					}
				} else {
					record.put("__index", i + 1);
					if (!record.containsKey("testCaseId")) {
						record.put("testCaseId", scenario.getMethodName() + "-" + (i + 1));
					}
				}
			}
		}

		return testdata;
	}

	/**
	 * 
	 * @param metadata
	 * @return
	 */
	private static Object[][] getData(Map<String, Object> metadata) {

		String query = (String) metadata.get(params.SQLQUERY.name());
		if (isNotBlank(query)) {
			return DatabaseUtil.getRecordDataAsMap(query);
		}

		String jsonTable = (String) metadata.get(params.JSON_DATA_TABLE.name());
		if (isNotBlank(jsonTable)) {
			return JSONUtil.getJsonArrayOfMaps(jsonTable);
		}

		String file = (String) metadata.get(params.DATAFILE.name());
		String key = (String) metadata.get(params.KEY.name());

		if (isNotBlank(file)) {
			if (file.endsWith("json")) {
				return JSONUtil.getJsonArrayOfMaps(file);
			}
			if (file.endsWith("xml")) {
				List<Object[]> mapData = DataProviderUtil.getDataSetAsMap(key, file);
				return mapData.toArray(new Object[][] {});
			}
			if (file.endsWith("xls")) {
				if (isNotBlank(key)) {
					return ExcelUtil.getTableDataAsMap(file, ((String) metadata.get(params.KEY.name())),
							(String) metadata.get(params.SHEETNAME.name()));
				}
				return ExcelUtil.getExcelDataAsMap(file, (String) metadata.get(params.SHEETNAME.name()));
			}
			// csv, text
			List<Object[]> csvData = CSVUtil.getCSVDataAsMap(file);
			return csvData.toArray(new Object[][] {});
		}
		if (isNotBlank(key)) {
			List<Object[]> mapData = DataProviderUtil.getDataSetAsMap(key, "");
			return mapData.toArray(new Object[][] {});
		}
		throw new RuntimeException("No data provider found");
	}

	private static Iterator<Object[]> invokeCustomDataProvider(ITestNGMethod tm, ITestContext c, String dp,
			String dpc) {
		String methodClass = tm.getConstructorOrMethod().getDeclaringClass().getName();

		if (isBlank(dpc)) {
			dpc = getBundle().getString("global.dataproviderclass", getBundle().getString("dataproviderclass",methodClass));
		}
		if (isNotBlank(dpc)) {
			Method m;
			try {
				m = getDataProviderMethod(dp, dpc);
			} catch (Exception e) {
				m = getDataProviderMethod(dp, methodClass);
			}
			Object instanceToUse = ClassHelper.newInstanceOrNull(m.getDeclaringClass());
			return InvocatoinHelper.invokeDataProvider(instanceToUse, m, tm, c, null,
					new Configuration().getAnnotationFinder());
		} else {
			throw new DataProviderException(
					"Data-provider class not found. Please provide fully qualified class name as dataProviderClass");
		}
	}

	private static Method getDataProviderMethod(String dp, String dpc) {
		try {
			Class<?> dpClass = Class.forName(dpc);
			Set<Method> dpMethods = ClassUtil.getAllMethodsWithAnnotation(dpClass, DataProvider.class);
			for (Method m : dpMethods) {
				DataProvider dpObj = ClassUtil.getAnnotation(m, DataProvider.class);
				if (dp.equalsIgnoreCase(dpObj.name())) {
					// this is the mehod we are lo
					return m;
				}
			}
		} catch (ClassNotFoundException e) {
			throw new DataProviderException("Data-provider class " + dpc
					+ " not found. Please provide fully qualified class name as dataProviderClass");
		}
		throw new DataProviderException("Data-provider: '" + dp + "' not found in class: '" + dpc
				+ "'. Please provide valid data provider name as dataProvider");
	}

	private static class InvocatoinHelper extends MethodInvocationHelper {
		protected static Iterator<Object[]> invokeDataProvider(Object instance, Method dataProvider,
				ITestNGMethod method, ITestContext testContext, Object fedInstance,
				IAnnotationFinder annotationFinder) {
			return MethodInvocationHelper.invokeDataProvider(instance, dataProvider, method, testContext, fedInstance,
					annotationFinder);
		}
	}

	private static Set<QAFDataProviderIntercepter> getIntercepters() {
		Set<QAFDataProviderIntercepter> intercepters = new LinkedHashSet<QAFDataProviderIntercepter>();
		String[] listners = ConfigurationManager.getBundle().getStringArray(ApplicationProperties.QAF_LISTENERS.key);
		for (String listener : listners) {
			try {
				Class<?> listenerClass = Class.forName(listener);
				if (QAFDataProviderIntercepter.class.isAssignableFrom(listenerClass)) {
					QAFDataProviderIntercepter intercepter = (QAFDataProviderIntercepter) listenerClass.newInstance();
					intercepters.add(intercepter);
				}
			} catch (Exception e) {
			}
		}
		return intercepters;
	}
}
