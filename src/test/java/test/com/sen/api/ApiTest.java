package test.com.sen.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sen.api.beans.ApiDataBean;
import com.sen.api.configs.ApiConfig;
import com.sen.api.listeners.AutoTestListener;
import com.sen.api.listeners.RetryListener;
import com.sen.api.utils.*;
import io.qameta.allure.Step;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.dom4j.DocumentException;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.annotations.*;
import org.testng.annotations.Optional;

import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;

@Listeners({AutoTestListener.class, RetryListener.class})
public class ApiTest extends TestBase {

    /**
     * api请求跟路径
     */
    private static String rootUrl;

    /**
     * 钉钉通知地址
     */
    private static String webhook_Token;

    /**
     * 测试报告地址
     */
    private static String messageUrl;

    /**
     * 跟路径是否以‘/’结尾
     */
    private static boolean rooUrlEndWithSlash = false;

    /**
     * 所有公共header，会在发送请求的时候添加到http header上
     */
    private static Header[] publicHeaders;

    /**
     * 是否使用form-data传参 会在post与put方法封装请求参数用到
     */
    private static boolean requestByFormData = false;

    /**
     * 配置
     */
    private static ApiConfig apiConfig;

    /**
     * 所有api测试用例数据
     */
    protected List<ApiDataBean> dataList = new ArrayList<ApiDataBean>();

    private static HttpClient client;

    /**
     * 初始化测试数据
     *
     * @throws Exception
     */
    @Parameters("envName")
    @BeforeSuite
    public void init(@Optional("api-config.xml") String envName) throws Exception {
        String configFilePath = Paths.get(System.getProperty("user.dir"), envName).toString();
        ReportUtil.log("api config path:" + configFilePath);
        apiConfig = new ApiConfig(configFilePath);
        // 获取基础数据
        rootUrl = apiConfig.getRootUrl();
        webhook_Token = apiConfig.getWebhook_Token();
        messageUrl = apiConfig.getMessageUrl();
        rooUrlEndWithSlash = rootUrl.endsWith("/");

        // 读取 param，并将值保存到公共数据map
        Map<String, String> params = apiConfig.getParams();
        setSaveDates(params);
        System.err.println("params-------" + params);
//        Map<String, String> header1 = apiConfig.getHeaders();
//        setHeeader(header1);
//        System.err.println("header-------" + header1);


        List<Header> headers = new ArrayList<Header>();
        apiConfig.getHeaders().forEach((key, value) -> {
            Header header = new BasicHeader(key, value);
            if (!requestByFormData && key.equalsIgnoreCase("content-type") && value.toLowerCase().contains("form-data")) {
                requestByFormData = true;
            }
            headers.add(header);
        });
        publicHeaders = headers.toArray(new Header[headers.size()]);
        System.err.println(headers.size());
        client = new SSLClient();
        client.getParams().setParameter(
                CoreConnectionPNames.CONNECTION_TIMEOUT, 60000); // 请求超时
        client.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 60000); // 读取超时
    }

    @Parameters({"excelPath", "sheetName"})
    @BeforeTest
    public void readData(@Optional("case/api-data.xls") String excelPath, @Optional("Sheet1") String sheetName) throws DocumentException {
        dataList = readExcelData(ApiDataBean.class, excelPath.split(";"),
                sheetName.split(";"));
    }

    /**
     * 过滤数据，run标记为Y的执行。
     *
     * @return
     * @throws DocumentException
     */
    @DataProvider(name = "apiDatas")
    public Iterator<Object[]> getApiData(ITestContext context)
            throws DocumentException {
        List<Object[]> dataProvider = new ArrayList<Object[]>();
        for (ApiDataBean data : dataList) {
            if (data.isRun()) {
                dataProvider.add(new Object[]{data});
            }
        }
        return dataProvider.iterator();
    }

    @Test(dataProvider = "apiDatas")
    public void apiTest(ApiDataBean apiDataBean) throws Exception {
        ReportUtil.log("--- test start ---");
        if (apiDataBean.getSleep() > 0) {
            // sleep休眠时间大于0的情况下进行暂停休眠
            ReportUtil.log(String.format("sleep %s seconds",
                    apiDataBean.getSleep()));
            Thread.sleep(apiDataBean.getSleep() * 1000);
        }
        String apiParam = buildRequestParam(apiDataBean);
        String header1 = buildRequesthead(apiDataBean);
        //把header转json->map格式
        Map<String, String> header = (Map) JSONObject.parse(header1);
        // 封装请求方法
        HttpUriRequest method = parseHttpRequest(header, apiDataBean.getUrl(),
                apiDataBean.getMethod(), apiParam);
        String responseData;

        try {
            // 执行
            HttpResponse response = client.execute(method);
            int responseStatus = response.getStatusLine().getStatusCode();
            ReportUtil.log("返回状态码：" + responseStatus);
            if (apiDataBean.getStatus() != 0) {
                Assert.assertEquals(responseStatus, apiDataBean.getStatus(),
                        "返回状态码与预期不符合!");
            }
//			else {
//				// 非2开头状态码为异常请求，抛异常后会进行重跑
//				if (200 > responseStatus || responseStatus >= 300) {
//					ReportUtil.log("返回状态码非200开头："+EntityUtils.toString(response.getEntity(), "UTF-8"));
//					throw new ErrorRespStatusException("返回状态码异常："
//							+ responseStatus);
//				}
//			}
            HttpEntity respEntity = response.getEntity();
            Header respContentType = response.getFirstHeader("Content-Type");
            if (respContentType != null && respContentType.getValue() != null
                    && (respContentType.getValue().contains("download") || respContentType.getValue().contains("octet-stream"))) {
                String conDisposition = response.getFirstHeader(
                        "Content-disposition").getValue();
                String fileType = conDisposition.substring(
                        conDisposition.lastIndexOf("."),
                        conDisposition.length());
                String filePath = "download/" + RandomUtil.getRandom(8, false)
                        + fileType;
                InputStream is = response.getEntity().getContent();
                Assert.assertTrue(FileUtil.writeFile(is, filePath), "下载文件失败。");
                // 将下载文件的路径放到{"filePath":"xxxxx"}进行返回
                responseData = "{\"filePath\":\"" + filePath + "\"}";
            } else {
//				responseData = DecodeUtil.decodeUnicode(EntityUtils
//						.toString(respEntity));
                responseData = EntityUtils.toString(respEntity, "UTF-8");
            }
        } catch (Exception e) {
            throw e;
        } finally {
            method.abort();
        }
        // 输出返回数据log
        ReportUtil.log("resp:" + responseData);
        respondBody("header:" + header + "\r\nmethod:" + apiDataBean.getMethod() + "\r\nurl:" + apiDataBean.getUrl() + "\r\nparam:" + apiParam.replace("\r\n", "").replace("\n", ""));
        respondBody("responseData:" + responseData);
        // 验证预期信息
        verifyResult(responseData, apiDataBean.getVerify(),
                apiDataBean.isContains());

        // 对返回结果进行提取保存。
        saveResult(responseData, apiDataBean.getSave());
    }

    private String buildRequestParam(ApiDataBean apiDataBean) {
        // 分析处理预参数 （函数生成的参数）
        String preParam = buildParam(apiDataBean.getPreParam());
        savePreParam(preParam);// 保存预存参数 用于后面接口参数中使用和接口返回验证中

        // 处理参数
        String apiParam = buildParam(apiDataBean.getParam());
        return apiParam;
    }

    private String buildRequesthead(ApiDataBean apiDataBean) {
        // 处理参数
        String header = buildHeader(apiDataBean.getHeader());
        return header;
    }

    /**
     * 封装请求方法
     *
     * @param url    请求路径
     * @param method 请求方法
     * @param param  请求参数
     * @return 请求方法
     * @throws UnsupportedEncodingException
     */
    private HttpUriRequest parseHttpRequest(Map<String, String> header, String url, String method, String param) throws UnsupportedEncodingException {
        // 处理url
        url = parseUrl(url);
        ReportUtil.log("header:" + header);
        ReportUtil.log("method:" + method);
        ReportUtil.log("url:" + url);
        ReportUtil.log("param:" + param.replace("\r\n", "").replace("\n", ""));
        //upload表示上传，也是使用post进行请求
        if ("post".equalsIgnoreCase(method) || "upload".equalsIgnoreCase(method)) {
            // 封装post方法
            HttpPost postMethod = new HttpPost(url);
            //判断excel是否输入header值，没有填写取默认配置
            if (header == null || header.size() == 0) {
                System.err.println("hearder is  null");
                //获取默认配置api-config的header
                postMethod.setHeaders(publicHeaders);
            } else {
                System.err.println("hearder not is null");
                //postMethod.setHeader("Content-Type", "application/json");
                for (Map.Entry<String, String> entry : header.entrySet()) {
                    //遍历header值
                    postMethod.addHeader(entry.getKey(), entry.getValue());
                }
            }
            //如果请求头的content-type的值包含form-data 或者 请求方法为upload(上传)时采用MultipartEntity形式
            HttpEntity entity = parseEntity(param, requestByFormData || "upload".equalsIgnoreCase(method));
            postMethod.setEntity(entity);
            return postMethod;
        } else if ("put".equalsIgnoreCase(method)) {
            // 封装put方法
            HttpPut putMethod = new HttpPut(url);
            if (header == null || header.size() == 0) {
                System.err.println("hearder is  null");
                //获取默认配置api-config的header
                putMethod.setHeaders(publicHeaders);
            } else {
                System.err.println("hearder not is null");
                //postMethod.setHeader("Content-Type", "application/json");
                for (Map.Entry<String, String> entry : header.entrySet()) {
                    System.err.println(entry.getKey() + "--===------" + entry.getValue());
                    putMethod.addHeader(entry.getKey(), entry.getValue());
                }
            }
            HttpEntity entity = parseEntity(param, requestByFormData);
            putMethod.setEntity(entity);
            return putMethod;
        } else if ("delete".equalsIgnoreCase(method)) {
            // 封装delete方法
            HttpDelete deleteMethod = new HttpDelete(url);
            if (header == null || header.size() == 0) {
                System.err.println("hearder is  null");
                //获取默认配置api-config的header
                deleteMethod.setHeaders(publicHeaders);
            } else {
                System.err.println("hearder not is null");
                //postMethod.setHeader("Content-Type", "application/json");
                for (Map.Entry<String, String> entry : header.entrySet()) {
                    System.err.println(entry.getKey() + "--===------" + entry.getValue());
                    deleteMethod.addHeader(entry.getKey(), entry.getValue());
                }
            }
            return deleteMethod;
        } else {
            // 封装get方法
            HttpGet getMethod = new HttpGet(url);
            if (header == null || header.size() == 0) {
                System.err.println("hearder is  null");
                //获取默认配置api-config的header
                getMethod.setHeaders(publicHeaders);
            } else {
                System.err.println("hearder not is null");
                //postMethod.setHeader("Content-Type", "application/json");
                for (Map.Entry<String, String> entry : header.entrySet()) {
                    System.err.println(entry.getKey() + "--===------" + entry.getValue());
                    getMethod.addHeader(entry.getKey(), entry.getValue());
                }
            }
            return getMethod;
        }
    }

    /**
     * 格式化url,替换路径参数等。
     *
     * @param shortUrl
     * @return
     */
    private String parseUrl(String shortUrl) {
        // 替换url中的参数
        shortUrl = getCommonParam(shortUrl);
        if (shortUrl.startsWith("http")) {
            return shortUrl;
        }
        if (rooUrlEndWithSlash == shortUrl.startsWith("/")) {
            if (rooUrlEndWithSlash) {
                shortUrl = shortUrl.replaceFirst("/", "");
            } else {
                shortUrl = "/" + shortUrl;
            }
        }
        return rootUrl + shortUrl;
    }

    /**
     * 格式化参数，如果是from-data格式则将参数封装到MultipartEntity否则封装到StringEntity
     *
     * @param param    参数
     * @param formData 是否使用form-data格式
     * @return
     * @throws UnsupportedEncodingException
     */
    private HttpEntity parseEntity(String param, boolean formData) throws UnsupportedEncodingException {
        if (formData) {
            Map<String, String> paramMap = JSON.parseObject(param,
                    HashMap.class);
            MultipartEntity multiEntity = new MultipartEntity();
            for (String key : paramMap.keySet()) {
                String value = paramMap.get(key);
                Matcher m = funPattern.matcher(value);
                if (m.matches() && m.group(1).equals("bodyfile")) {
                    value = m.group(2);
                    multiEntity.addPart(key, new FileBody(new File(value)));
                } else {
                    multiEntity.addPart(key, new StringBody(paramMap.get(key)));
                }
            }
            return multiEntity;
        } else {
            return new StringEntity(param, "UTF-8");
        }
    }

    //@AfterTest
    public void testc() {
        String bodyString = "====测试内容==";
        //测试报告展现 请求报文
        respondBody("111");

    }

    //@Step("请求参数打印")
    public void requestBody(String URL, String Body) {
        //报告展现请求报文
    }

    @Step("测试返回结果打印")
    public void respondBody(String Respond) {
        //报告展现响应报文
    }

    //通知钉钉消息
//    @AfterClass
    public void result_notification() throws Exception {
        HttpClient httpclient = HttpClients.createDefault();

        System.err.println("+============>>>>>>" + webhook_Token);
        HttpPost httppost = new HttpPost(webhook_Token);
        httppost.addHeader("Content-Type", "application/json; charset=utf-8");


        String textMsg = "{ \"msgtype\": \"text\", \"text\": {\"content\": \"我就是我, 是不一样的烟火\"}}";
        String textMsg2 = "{\"msgtype\": \"link\",\"link\": {\"text\":\"运营商管理后台API接口用例\",\"title\": \"API自动化测试结果\",\"picUrl\": \"\",\"messageUrl\": \"" + messageUrl + "\"}}";
        StringEntity se = new StringEntity(textMsg2, "utf-8");
        httppost.setEntity(se);

        HttpResponse response = httpclient.execute(httppost);
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            String result = EntityUtils.toString(response.getEntity(), "utf-8");
            System.out.println(result);
        }

    }

}
