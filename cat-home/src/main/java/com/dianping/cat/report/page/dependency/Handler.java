package com.dianping.cat.report.page.dependency;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.ServletException;

import org.hsqldb.lib.StringUtil;
import org.unidal.lookup.annotation.Inject;
import org.unidal.tuple.Pair;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.cat.Constants;
import com.dianping.cat.ServerConfigManager;
import com.dianping.cat.consumer.dependency.DependencyAnalyzer;
import com.dianping.cat.consumer.dependency.DependencyReportMerger;
import com.dianping.cat.consumer.dependency.model.entity.Dependency;
import com.dianping.cat.consumer.dependency.model.entity.DependencyReport;
import com.dianping.cat.consumer.dependency.model.entity.Index;
import com.dianping.cat.consumer.dependency.model.entity.Segment;
import com.dianping.cat.consumer.state.StateAnalyzer;
import com.dianping.cat.consumer.state.model.entity.Machine;
import com.dianping.cat.consumer.state.model.entity.StateReport;
import com.dianping.cat.helper.TimeHelper;
import com.dianping.cat.home.dal.report.Event;
import com.dianping.cat.home.dependency.graph.entity.TopologyEdge;
import com.dianping.cat.home.dependency.graph.entity.TopologyGraph;
import com.dianping.cat.home.dependency.graph.entity.TopologyNode;
import com.dianping.cat.home.dependency.graph.transform.DefaultJsonBuilder;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.page.LineChart;
import com.dianping.cat.report.page.PayloadNormalizer;
import com.dianping.cat.report.page.dependency.dashboard.ProductLinesDashboard;
import com.dianping.cat.report.page.dependency.graph.LineGraphBuilder;
import com.dianping.cat.report.page.dependency.graph.TopologyGraphManager;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.dianping.cat.service.ModelRequest;
import com.dianping.cat.service.ModelResponse;

public class Handler implements PageHandler<Context> {

	@Inject(type = ModelService.class, value = DependencyAnalyzer.ID)
	private ModelService<DependencyReport> m_dependencyService;

	@Inject(type = ModelService.class, value = StateAnalyzer.ID)
	private ModelService<StateReport> m_stateService;

	@Inject
	private TopologyGraphManager m_graphManager;

	@Inject
	private ExternalInfoBuilder m_externalInfoBuilder;

	@Inject
	private JspViewer m_jspViewer;

	@Inject
	private PayloadNormalizer m_normalizePayload;

	@Inject
	private ServerConfigManager m_configManager;

	public static final String TUAN_TOU = "TuanGou";
	
	private Segment buildAllSegmentsInfo(DependencyReport report) {
		Segment result = new Segment();
		Map<Integer, Segment> segments = report.getSegments();
		DependencyReportMerger merger = new DependencyReportMerger(null);

		for (Segment segment : segments.values()) {
			Map<String, Dependency> dependencies = segment.getDependencies();
			Map<String, Index> indexs = segment.getIndexs();

			for (Index index : indexs.values()) {
				Index temp = result.findOrCreateIndex(index.getName());
				merger.mergeIndex(temp, index);
			}
			for (Dependency dependency : dependencies.values()) {
				Dependency temp = result.findOrCreateDependency(dependency.getKey());

				merger.mergeDependency(temp, dependency);
			}
		}
		return result;
	}

	private String buildCatInfoMessage(StateReport report) {
		int realSize = report.getMachines().size();
		List<Pair<String, Integer>> servers = m_configManager.getConsoleEndpoints();
		int excepeted = servers.size();
		Set<String> errorServers = new HashSet<String>();

		if (realSize != excepeted) {
			for (Pair<String, Integer> server : servers) {
				String serverIp = server.getKey();

				if (report.getMachines().get(serverIp) == null) {
					errorServers.add(serverIp);
				}
			}
		}
		for (Machine machine : report.getMachines().values()) {
			if (machine.getTotalLoss() > 100 * 10000) {
				errorServers.add(machine.getIp());
			}
		}

		if (errorServers.size() > 0) {
			return errorServers.toString();
		} else {
			return null;
		}
	}


	private void buildDependencyDashboard(Model model, Payload payload, Date reportTime) {
		ProductLinesDashboard dashboardGraph = m_graphManager.buildDependencyDashboard(reportTime.getTime());
		Map<String, List<TopologyNode>> dashboardNodes = dashboardGraph.getNodes();

		for (Entry<String, List<TopologyNode>> entry : dashboardNodes.entrySet()) {
			for (TopologyNode node : entry.getValue()) {
				m_externalInfoBuilder.buildNodeZabbixInfo(node, model, payload);
			}
		}
		model.setReportStart(new Date(payload.getDate()));
		model.setReportEnd(new Date(payload.getDate() + TimeHelper.ONE_HOUR - 1));
		model.setDashboardGraph(dashboardGraph.toJson());
		model.setDashboardGraphData(dashboardGraph);
	}

	private void buildDependencyLineChart(Model model, Payload payload, Date reportTime) {
		DependencyReport dependencyReport = queryDependencyReport(payload);
		buildHourlyReport(dependencyReport, model, payload);
		buildHourlyLineGraph(dependencyReport, model);

		Segment segment = dependencyReport.findSegment(model.getMinute());
		Map<String, List<String>> dependency = parseDependencies(segment);

		model.setEvents(m_externalInfoBuilder.queryDependencyEvent(dependency, model.getDomain(), reportTime));
	}

	private void buildExceptionDashboard(Model model, Payload payload, long date) {
		model.setReportStart(new Date(payload.getDate()));
		model.setReportEnd(new Date(payload.getDate() + TimeHelper.ONE_HOUR - 1));
		m_externalInfoBuilder.buildTopErrorInfo(payload, model);
	}

	private void buildHourlyLineGraph(DependencyReport report, Model model) {
		LineGraphBuilder builder = new LineGraphBuilder();

		builder.visitDependencyReport(report);

		List<LineChart> index = builder.queryIndex();
		Map<String, List<LineChart>> dependencys = builder.queryDependencyGraph();

		model.setIndexGraph(buildLineChartGraph(index));
		model.setDependencyGraph(buildLineChartGraphs(dependencys));
	}

	private void buildHourlyReport(DependencyReport report, Model model, Payload payload) {
		Segment segment = report.findSegment(model.getMinute());

		model.setReport(report);
		model.setSegment(segment);

		if (payload.isAll()) {
			model.setSegment(buildAllSegmentsInfo(report));
		}
	}

	private List<String> buildLineChartGraph(List<LineChart> charts) {
		List<String> result = new ArrayList<String>();

		for (LineChart temp : charts) {
			result.add(temp.getJsonString());
		}
		return result;
	}

	private Map<String, List<String>> buildLineChartGraphs(Map<String, List<LineChart>> charts) {
		Map<String, List<String>> result = new HashMap<String, List<String>>();

		for (Entry<String, List<LineChart>> temp : charts.entrySet()) {
			result.put(temp.getKey(), buildLineChartGraph(temp.getValue()));
		}
		return result;
	}

	private void buildProjectTopology(Model model, Payload payload, Date reportTime) {
		TopologyGraph topologyGraph = m_graphManager.buildTopologyGraph(model.getDomain(), reportTime.getTime());
		Map<String, List<String>> graphDependency = parseDependencies(topologyGraph);
		Map<String, List<Event>> externalErrors = m_externalInfoBuilder.queryDependencyEvent(graphDependency,
		      model.getDomain(), reportTime);

		DependencyReport report = queryDependencyReport(payload);
		buildHourlyReport(report, model, payload);
		model.setEvents(externalErrors);
		m_externalInfoBuilder.buildZabbixErrorOnGraph(topologyGraph,
		      m_externalInfoBuilder.buildZabbixHeader(payload, model), externalErrors);
		m_externalInfoBuilder.buildExceptionInfoOnGraph(payload, model, topologyGraph);
		model.setReportStart(new Date(payload.getDate()));
		model.setReportEnd(new Date(payload.getDate() + TimeHelper.ONE_HOUR - 1));
		String build = new DefaultJsonBuilder().build(topologyGraph);

		model.setTopologyGraph(build);
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = DependencyAnalyzer.ID)
	public void handleInbound(Context ctx) throws ServletException, IOException {
	}

	@Override
	@OutboundActionMeta(name = DependencyAnalyzer.ID)
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();

		normalize(model, payload);

		Action action = payload.getAction();
		long date = payload.getDate();
		Date reportTime = new Date(date + TimeHelper.ONE_MINUTE * model.getMinute());

		switch (action) {
		case TOPOLOGY:
			buildProjectTopology(model, payload, reportTime);
			break;
		case LINE_CHART:
			buildDependencyLineChart(model, payload, reportTime);
			break;
		case DEPENDENCY_DASHBOARD:
			buildDependencyDashboard(model, payload, reportTime);
			break;
		case EXCEPTION_DASHBOARD:
			buildExceptionDashboard(model, payload, date);
			
			StateReport report = queryHourlyReport(payload);
			model.setMessage(buildCatInfoMessage(report));
			break;
		}
		m_jspViewer.view(ctx, model);
	}

	private void normalize(Model model, Payload payload) {
		model.setPage(ReportPage.DEPENDENCY);
		model.setAction(Action.LINE_CHART);

		m_normalizePayload.normalize(model, payload);

		Integer minute = parseQueryMinute(payload);
		int maxMinute = 60;
		List<Integer> minutes = new ArrayList<Integer>();

		if (payload.getPeriod().isCurrent()) {
			long current = System.currentTimeMillis() / 1000 / 60;
			maxMinute = (int) (current % (60));
		}
		for (int i = 0; i < 60; i++) {
			minutes.add(i);
		}
		model.setMinute(minute);
		model.setMaxMinute(maxMinute);
		model.setMinutes(minutes);
	}

	private Map<String, List<String>> parseDependencies(Segment segment) {
		Map<String, List<String>> results = new TreeMap<String, List<String>>();
		if (segment != null) {
			Map<String, Dependency> dependencies = segment.getDependencies();

			for (Dependency temp : dependencies.values()) {
				String type = temp.getType();
				String target = temp.getTarget();
				List<String> targets = results.get(type);

				if (targets == null) {
					targets = new ArrayList<String>();
					results.put(type, targets);
				}
				targets.add(target);
			}
		}
		return results;
	}

	private Map<String, List<String>> parseDependencies(TopologyGraph graph) {
		Map<String, List<String>> dependencies = new HashMap<String, List<String>>();
		Map<String, TopologyEdge> edges = graph.getEdges();

		for (TopologyEdge temp : edges.values()) {
			String type = temp.getType();
			String target = temp.getTarget();

			List<String> targets = dependencies.get(type);
			if (targets == null) {
				targets = new ArrayList<String>();
				dependencies.put(type, targets);
			}
			targets.add(target);
		}
		return dependencies;
	}

	private int parseQueryMinute(Payload payload) {
		int minute = 0;
		String min = payload.getMinute();

		if (StringUtil.isEmpty(min)) {
			long current = System.currentTimeMillis() / 1000 / 60;
			minute = (int) (current % (60));
		} else {
			minute = Integer.parseInt(min);
		}

		return minute;
	}

	private DependencyReport queryDependencyReport(Payload payload) {
		String domain = payload.getDomain();
		ModelRequest request = new ModelRequest(domain, payload.getDate());

		if (m_dependencyService.isEligable(request)) {
			ModelResponse<DependencyReport> response = m_dependencyService.invoke(request);
			DependencyReport report = response.getModel();

			if (report != null && report.getStartTime() == null) {
				report.setStartTime(new Date(payload.getDate()));
				report.setStartTime(new Date(payload.getDate() + TimeHelper.ONE_HOUR));
			}
			return report;
		} else {
			throw new RuntimeException("Internal error: no eligable dependency service registered for " + request + "!");
		}
	}

	private StateReport queryHourlyReport(Payload payload) {
		String domain = Constants.CAT;
		ModelRequest request = new ModelRequest(domain, payload.getDate()) //
		      .setProperty("ip", payload.getIpAddress());

		if (m_stateService.isEligable(request)) {
			ModelResponse<StateReport> response = m_stateService.invoke(request);

			return response.getModel();
		} else {
			throw new RuntimeException("Internal error: no eligable sql service registered for " + request + "!");
		}
	}

}
