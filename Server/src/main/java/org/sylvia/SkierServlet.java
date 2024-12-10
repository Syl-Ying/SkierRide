package org.sylvia;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.swagger.client.model.LiftRide;
import io.swagger.client.model.ResponseMsg;

import static org.sylvia.Constant.*;


@WebServlet(name = "SkierServlet", value = "/skiers/*")
public class SkierServlet extends HttpServlet {

    private Gson gson = new Gson();
    private static final Integer NUM_CHANNELS = 30;
    private static final Logger logger = Logger.getLogger(SkierServlet.class.getName());
    private BlockingQueue<Channel> channelPool;

    @Override
    public void init() {
        try {
            logger.info("Initializing RabbitMQ connection and channel pool...");
            super.init();
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(RABBITMQ_ELASTIC_IP);
            factory.setPort(RABBITMQ_PORT);
            factory.setUsername(RABBITMQ_USERNAME);
            factory.setPassword(RABBITMQ_PASSWORD);
            Connection connection = factory.newConnection();

            channelPool = new LinkedBlockingDeque<>();
            for (int i = 0; i < NUM_CHANNELS; i++) {
                channelPool.add(connection.createChannel());
            }
            logger.info("RabbitMQ connection and channel pool initialized successfully.");
        } catch (ServletException | IOException | TimeoutException e) {
            logger.severe("Failed to initialize RabbitMQ connection or channels: " + e.getMessage());
            throw new RuntimeException("Failed to initialize RabbitMQ connection or channels", e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("text/plain");
        String pathInfo = req.getPathInfo();

        // validate url path and return response code and maybe some value if input is valid
        if (!isUrlPathValid(pathInfo, resp)) {
            return;
        } else {
            resp.setStatus(HttpServletResponse.SC_OK); // 200
            // TODO: process url params in `urlParts`
            resp.getWriter().write("200 It Works!");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // logger.info("Received POST request: " + req.getPathInfo());
        String pathInfo = req.getPathInfo(); // /{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}:
        if (!isUrlPathValid(pathInfo, resp)) {
            logger.warning("Invalid URL path: " + pathInfo);
            return;
        }
        // Parse JSon body(time, liftId) using req.getReader() which returns a BufferedReader,
        LiftRide liftRide = gson.fromJson(req.getReader(), LiftRide.class);
        if (!isUrlBodyValid(liftRide, resp)) {
            logger.warning("Invalid Request body: " + liftRide.toString());
            return;
        }

        // Send the formatted message to RabbitMQ
        try {
            RateLimiter.decorateRunnable(ResilienceConfig.getRateLimiter(), () -> {
                try {
                    handleRequest(req, resp, liftRide);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).run();
        }  catch (RequestNotPermitted e) {
            logger.warning("Rate limit exceeded. Rejecting request.");
            resp.sendError(429, "Too many requests"); // no 429 in static variable
            writeResponse(resp, "Rate limit exceeded. Please try again later.");
        }
    }

    private Boolean isUrlPathValid(String urlPath, HttpServletResponse resp) throws IOException {
        // check if the url exists
        if (urlPath == null || urlPath.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);  // 404
            resp.getWriter().write(gson.toJson(new ResponseMsg().message("Missing parameters")));
            return false;
        }

        String[] pathParts = urlPath.split("/");

        // validate the request url path according to the API spec
        // urlPath  = "/1/seasons/2019/day/1/skier/123"
        // pathParts = [, 1, seasons, 2019, day, 1, skier, 123]
        if (pathParts.length != 8) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST); // 400
            writeResponse(resp, "Invalid path, expected format: /{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}");
            return false;
        }

        try {
            // Validate resortID
            int resortID = Integer.parseInt(pathParts[1]);
            if (resortID <= 0) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                writeResponse(resp, "Invalid resortID. Must be a positive integer.");
                return false;
            }

            // Validate seasonID
            String seasonID = pathParts[3];
            int seasonYear = Integer.parseInt(seasonID);
            int currentYear = Year.now().getValue();
            if (seasonYear < 1900 || seasonYear > currentYear) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                writeResponse(resp, "Invalid seasonID. Must be a valid year between 1900 and " + currentYear + " .");
                return false;
            }

            // Validate dayID range (1-366)
            int dayID = Integer.parseInt(pathParts[5]);
            if (dayID < 1 || dayID > 366) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                writeResponse(resp, "Invalid dayID, must be between 1 and 366.");
                return false;
            }

            // Validate skierID
            int skierID = Integer.parseInt(pathParts[7]);
            if (skierID <= 0) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                writeResponse(resp, "Invalid skierID. Must be a positive integer.");
                return false;
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeResponse(resp, "Invalid parameter format. resortID, dayID, and skierID must be integers.");
            return false;
        }

        return true;
    }

    private Boolean isUrlBodyValid(LiftRide liftRide, HttpServletResponse resp) throws IOException {
        if (liftRide != null && liftRide.getLiftID() != null && liftRide.getTime() != null) {
            return true;
        }
        writeResponse(resp, "Request body is incorrect!");
        return false;
    }

    private void writeResponse(HttpServletResponse resp, String message) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(gson.toJson(new ResponseMsg().message(message)));
    }

    private void handleRequest(HttpServletRequest req, HttpServletResponse resp, LiftRide liftRide)
            throws IOException, InterruptedException {
        String pathInfo = req.getPathInfo();
        String[] pathParts = pathInfo.split("/");
        int resortID = Integer.parseInt(pathParts[1]);
        String seasonID = pathParts[3];
        int dayID = Integer.parseInt(pathParts[5]);
        int skierID = Integer.parseInt(pathParts[7]);

        // Format the payload to be sent to the remote queue
        JsonObject msg = new JsonObject();
        msg.add("skierID", new JsonPrimitive(skierID));
        msg.add("resortID", new JsonPrimitive(resortID));
        msg.add("seasonID", new JsonPrimitive(seasonID));
        msg.add("dayID", new JsonPrimitive(dayID));
        msg.add("time", new JsonPrimitive(liftRide.getTime()));
        msg.add("liftID", new JsonPrimitive(liftRide.getLiftID()));

        sendToQueue(msg.toString());

        // Return success response
        resp.setStatus(HttpServletResponse.SC_CREATED); // 201
        writeResponse(resp, String.format("Lift ride stored for skierID %d at resort %d on day %d in %s",
                skierID, resortID, dayID, seasonID));
        logger.info("POST request processed successfully for skierID: " + skierID);
    }

    private void sendToQueue(String msg) {
        Channel channel = null;
        try {
            channel = channelPool.take();
            channel.queueDeclare(RABBITMQ_NAME, false, false, false, null);
            channel.basicPublish("", RABBITMQ_NAME, null, msg.getBytes(StandardCharsets.UTF_8));
            logger.info("Message published to queue: " + RABBITMQ_NAME);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
            logger.severe("Thread interrupted while sending message to queue: " + e.getMessage());
        } catch (IOException e) {
            logger.severe("IO exception while sending message to queue: " + e.getMessage());
            throw new RuntimeException("Failed to send message to RabbitMQ", e); // Wrap in a runtime exception
        } finally {
            // Return the channel to the pool
            if (channel != null) {
                channelPool.offer(channel);
                // logger.info("Channel returned to the pool.");
            }
        }
    }
}
