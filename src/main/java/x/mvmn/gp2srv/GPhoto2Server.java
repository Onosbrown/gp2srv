package x.mvmn.gp2srv;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.github.jknack.handlebars.Context;

import x.mvmn.gp2srv.mock.service.impl.MockCameraServiceImpl;
import x.mvmn.gp2srv.web.service.CameraService;
import x.mvmn.gp2srv.web.service.TemplateEngine;
import x.mvmn.gp2srv.web.service.impl.CameraServiceImpl;
import x.mvmn.gp2srv.web.service.impl.HandlebarsTemplateEngineImpl;
import x.mvmn.gp2srv.web.servlets.AbstractErrorHandlingServlet;
import x.mvmn.gp2srv.web.servlets.BasicAuthFilter;
import x.mvmn.gp2srv.web.servlets.CameraControlServlet;
import x.mvmn.gp2srv.web.servlets.DevModeServlet;
import x.mvmn.gp2srv.web.servlets.LiveViewServlet;
import x.mvmn.gp2srv.web.servlets.StaticsResourcesServlet;
import x.mvmn.jlibgphoto2.GP2Camera;
import x.mvmn.lang.util.Provider;
import x.mvmn.log.PrintStreamLogger;
import x.mvmn.log.api.Logger;
import x.mvmn.log.api.Logger.LogLevel;
import x.mvmn.util.FileBackedProperties;

public class GPhoto2Server implements Provider<TemplateEngine<Context>> {

	public static final String STATIC_RESOURCES_CLASSPATH_PREFIX = "/x/mvmn/gp2srv/web/static";
	public static final String TEMPLATE_RESOURCES_CLASSPATH_PREFIX = "/x/mvmn/gp2srv/web/templates";

	private static final String DEFAULT_CONTEXT_PATH = "/";
	private static final int DEFAULT_PORT = 8080;

	private final Server server;
	private final Logger logger;
	private final TemplateEngine<Context> templateEngine;
	private final GP2Camera camera;

	private final File userHome;
	private final File appHomeFolder;
	private final File favouredCamConfSettingsFile;
	private final FileBackedProperties favouredCamConfSettings;

	public static final AtomicBoolean liveViewEnabled = new AtomicBoolean(true);
	public static final AtomicBoolean liveViewInProgress = new AtomicBoolean(false);

	public GPhoto2Server(final LogLevel logLevel, final boolean mockMode) {
		this(DEFAULT_CONTEXT_PATH, DEFAULT_PORT, logLevel, mockMode);
	}

	public GPhoto2Server(final LogLevel logLevel) {
		this(DEFAULT_CONTEXT_PATH, DEFAULT_PORT, logLevel, false);
	}

	public GPhoto2Server(Integer port, final LogLevel logLevel, final boolean mockMode) {
		this(DEFAULT_CONTEXT_PATH, port, logLevel, mockMode);
	}

	public GPhoto2Server(Integer port, final LogLevel logLevel) {
		this(DEFAULT_CONTEXT_PATH, port, logLevel, false);
	}

	public GPhoto2Server(String contextPath, Integer port, final LogLevel logLevel, final boolean mockMode) {
		this(contextPath, port, logLevel, mockMode, null);
	}

	public GPhoto2Server(Integer port, final LogLevel logLevel, final boolean mockMode, final String[] requireAuthCredentials) {
		this(DEFAULT_CONTEXT_PATH, port, logLevel, mockMode, requireAuthCredentials);
	}

	public GPhoto2Server(String contextPath, Integer port, final LogLevel logLevel, final boolean mockMode, final String[] requireAuthCredentials) {
		this.logger = makeLogger(logLevel);

		this.camera = mockMode ? null : new GP2Camera();

		logger.info("Initializing...");

		try {
			if (contextPath == null) {
				contextPath = DEFAULT_CONTEXT_PATH;
			}
			if (port == null) {
				port = DEFAULT_PORT;
			}

			this.templateEngine = makeTemplateEngine();

			this.server = new Server(port);
			this.server.setStopAtShutdown(true);

			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
			context.setContextPath(contextPath);
			userHome = new File(System.getProperty("user.home"));
			appHomeFolder = new File(userHome, ".gp2srv");
			appHomeFolder.mkdir();

			favouredCamConfSettingsFile = new File(appHomeFolder, "favouredConfs.properties");
			if (!favouredCamConfSettingsFile.exists()) {
				favouredCamConfSettingsFile.createNewFile();
			}
			favouredCamConfSettings = new FileBackedProperties(favouredCamConfSettingsFile);

			// TODO: Provide to camera control servlet
			// velocityContextService.getGlobalContext().put("favouredCamConfSettings", favouredCamConfSettings);

			context.setErrorHandler(new ErrorHandler() {
				private final AbstractErrorHandlingServlet eh = new AbstractErrorHandlingServlet(GPhoto2Server.this, GPhoto2Server.this.getLogger()) {
					private static final long serialVersionUID = -30520483617261093L;
				};

				@Override
				protected void handleErrorPage(final HttpServletRequest request, final Writer writer, final int code, final String message) {
					eh.serveGenericErrorPage(request, writer, code, message);
				}
			});

			if (requireAuthCredentials != null && requireAuthCredentials.length > 1 && requireAuthCredentials[0] != null && requireAuthCredentials[1] != null
					&& !requireAuthCredentials[0].trim().isEmpty() && !requireAuthCredentials[1].trim().isEmpty()) {
				context.addFilter(new FilterHolder(new BasicAuthFilter(requireAuthCredentials[0], requireAuthCredentials[1])), "/*",
						EnumSet.of(DispatcherType.REQUEST));
			}

			// context.addServlet(new ServletHolder(new WebSocketServlet() {
			// private static final long serialVersionUID = 2418523268586680763L;
			//
			// @Override
			// public void configure(final WebSocketServletFactory factory) {
			// factory.setCreator(new WebSocketCreator() {
			// @Override
			// public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
			// req.getRequestURI();
			// // TODO Auto-generated method stub
			// return null;
			// }
			// });
			// }
			// }), "/ws/*");

			final CameraService cameraService = mockMode ? new MockCameraServiceImpl() : new CameraServiceImpl(camera);

			// context.addServlet(new ServletHolder(new ImagesServlet(this, imagesFolder, logger)), "/img/*");
			context.addServlet(new ServletHolder(new LiveViewServlet(cameraService)), "/stream.mjpeg");
			context.addServlet(new ServletHolder(new StaticsResourcesServlet(this, logger)), "/static/*");
			context.addServlet(new ServletHolder(new DevModeServlet(this)), "/devmode/*");
			context.addServlet(new ServletHolder(new CameraControlServlet(cameraService, favouredCamConfSettings, this, logger)), "/*");

			server.setHandler(context);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		logger.info("Initializing: done.");
	}

	public void reReadTemplates() {
		// TODO: Implement
	}

	protected Logger makeLogger(final LogLevel logLevel) {
		return new PrintStreamLogger(System.out).setLevel(logLevel);
	}

	public Logger getLogger() {
		return this.logger;
	}

	protected TemplateEngine<Context> makeTemplateEngine() throws IOException {
		return new HandlebarsTemplateEngineImpl();
	}

	public static void waitWhileLiveViewInProgress(int waitTime) {
		while (GPhoto2Server.liveViewInProgress.get() && waitTime-- > 0) {
			Thread.yield();
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
	}

	public GPhoto2Server start() throws Exception {
		logger.info("Starting server...");
		this.server.start();
		logger.info("Starting server: done.");
		return this;
	}

	public GPhoto2Server stop() throws Exception {
		this.server.stop();
		return this;
	}

	public GPhoto2Server join() throws Exception {
		this.server.join();
		return this;
	}

	public TemplateEngine<Context> provide() {
		return templateEngine;
	}

	public GP2Camera getCamera() {
		return camera;
	}
}
