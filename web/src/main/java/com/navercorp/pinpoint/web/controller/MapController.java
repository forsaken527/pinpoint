package com.nhn.pinpoint.web.controller;

import com.nhn.pinpoint.web.applicationmap.MapWrap;
import com.nhn.pinpoint.web.applicationmap.histogram.Histogram;
import com.nhn.pinpoint.web.service.MapService;
import com.nhn.pinpoint.web.util.Limiter;
import com.nhn.pinpoint.web.view.ResponseTimeViewModel;
import com.nhn.pinpoint.web.vo.Application;
import com.nhn.pinpoint.web.applicationmap.histogram.NodeHistogram;
import com.nhn.pinpoint.web.vo.Range;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.nhn.pinpoint.web.applicationmap.ApplicationMap;
import com.nhn.pinpoint.web.util.TimeUtils;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * @author emeroad
 * @author netspider
 */
@Controller
public class MapController {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
	private MapService mapService;

    @Autowired
    private Limiter dateLimit;

	/**
	 * FROM ~ TO기간의 서버 맵 데이터 조회
	 *
	 * @param applicationName
	 * @param serviceTypeCode
	 * @param from
	 * @param to
	 * @return
	 */
	@RequestMapping(value = "/getServerMapData", method = RequestMethod.GET)
    @ResponseBody
	public MapWrap getServerMapData(
									@RequestParam("applicationName") String applicationName,
									@RequestParam("serviceTypeCode") short serviceTypeCode,
									@RequestParam("from") long from,
									@RequestParam("to") long to) {
        final Range range = new Range(from, to);
        this.dateLimit.limit(from, to);
        logger.debug("range:{}", TimeUnit.MILLISECONDS.toMinutes(range.getRange()));
        Application application = new Application(applicationName, serviceTypeCode);

        ApplicationMap map = mapService.selectApplicationMap(application, range);

		return new MapWrap(map);
	}

	/**
	 * Period before 부터 현재시간까지의 서버맵 조회.
	 * 
	 * @param applicationName
	 * @param serviceTypeCode
	 * @param period
	 * @return
	 */
	@RequestMapping(value = "/getLastServerMapData", method = RequestMethod.GET)
    @ResponseBody
	public MapWrap getLastServerMapData(
										@RequestParam("applicationName") String applicationName,
										@RequestParam("serviceTypeCode") short serviceTypeCode,
										@RequestParam("period") long period) {
		
		long to = TimeUtils.getDelayLastTime();
		long from = to - period;
		return getServerMapData(applicationName, serviceTypeCode, from, to);
	}

	/**
     * 맵에서 직접 찍어오는걸로 변경시 잘 사용하지 않는 API가 될것임.
	 * 필터가 사용되지 않은 서버맵의 연결선을 통과하는 요청의 통계정보 조회
	 * 
	 * @param model
	 * @param from
	 * @param to
	 * @param sourceApplicationName
	 * @param sourceServiceType
	 * @param targetApplicationName
	 * @param targetServiceType
	 * @return
	 */
    @Deprecated
	@RequestMapping(value = "/linkStatistics", method = RequestMethod.GET)
	public String getLinkStatistics(Model model,
									@RequestParam("from") long from,
									@RequestParam("to") long to,
									@RequestParam("sourceApplicationName") String sourceApplicationName,
									@RequestParam("sourceServiceType") short sourceServiceType,
									@RequestParam("targetApplicationName") String targetApplicationName,
									@RequestParam("targetServiceType") short targetServiceType) {

        final Application sourceApplication = new Application(sourceApplicationName, sourceServiceType);
        final Application destinationApplication = new Application(targetApplicationName, targetServiceType);
        final Range range = new Range(from, to);

        NodeHistogram nodeHistogram = mapService.linkStatistics(sourceApplication, destinationApplication, range);

		model.addAttribute("range", range);

        model.addAttribute("sourceApplication", sourceApplication);

        model.addAttribute("targetApplication", destinationApplication);

        Histogram applicationHistogram = nodeHistogram.getApplicationHistogram();
		model.addAttribute("linkStatistics", applicationHistogram);


        List<ResponseTimeViewModel> applicationTimeSeriesHistogram = nodeHistogram.getApplicationTimeHistogram();
        String applicationTimeSeriesHistogramJson = null;
        try {
            applicationTimeSeriesHistogramJson = MAPPER.writeValueAsString(applicationTimeSeriesHistogram);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        model.addAttribute("timeSeriesHistogram", applicationTimeSeriesHistogramJson);

        // 결과의 from, to를 다시 명시해야 되는듯 한데. 현재는 그냥 요청 데이터를 그냥 주는것으로 보임.
		model.addAttribute("resultFrom", from);
		model.addAttribute("resultTo", to);


		return "linkStatistics";
	}

    private final static ObjectMapper MAPPER = new ObjectMapper();
}