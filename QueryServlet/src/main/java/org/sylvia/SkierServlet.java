package org.sylvia;

import com.google.gson.Gson;
import io.swagger.client.model.ResponseMsg;
import io.swagger.client.model.SkierVertical;
import org.sylvia.config.RedisConfig;
import redis.clients.jedis.JedisPool;

import java.io.*;
import java.time.Year;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(name = "SkierServlet", value = "/skiers/*")
public class SkierServlet extends HttpServlet {

    private final ResponseMsg responseMsg = new ResponseMsg();
    private QueryDao queryDao;
    private final Gson gson = new Gson();
    private final Logger logger = Logger.getLogger(SkierServlet.class.getName());
    private JedisPool jedisPool;

    @Override
    public void init() throws ServletException {
        super.init();
        queryDao = QueryDao.getInstance();
        queryDao.testDynamoDbConnection();

        try {
            jedisPool = new JedisPool(RedisConfig.REDIS_URI, RedisConfig.PORT);
            System.out.println("JedisPool initialized successfully.");
        } catch (Exception e) {
            throw new ServletException("Failed to initialize JedisPool", e);
        }
    }

    /**
     * GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
     *  get the total vertical for the skier for the specified ski day
     * GET /skiers/{skierID}/vertical
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String path = req.getPathInfo();

        if (path == null || path.isEmpty()) {
            writeErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "Missing or invalid path");
            return;
        }

        String[] pathParts = path.split("/");
        if (validateUrlPath(pathParts)) {
            // GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
            if ("seasons".equals(pathParts[2])) {
                int totalVertical = getDailyVertical(pathParts);
                // logger.info("debug: totalVertical" + String.valueOf(totalVertical));
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(gson.toJson(totalVertical));
            } else if ("vertical".equals(pathParts[2])) {
                // GET /skiers/{skierID}/vertical
                String resortIDParam = req.getParameter("resortID");
                if (resortIDParam == null || resortIDParam.isEmpty()) {
                    writeErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "Missing query parameter");
                    return;
                }

                int resortID = Integer.parseInt(req.getParameter("resortID"));
                int skierID = Integer.parseInt(pathParts[1]);
                String season = req.getParameter("season"); // optional
                SkierVertical skierVertical = getResortVertical(skierID, resortID, season);
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(gson.toJson(skierVertical));
            }
        } else {
            writeErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid path format");
        }
    }

    @Override
    public void destroy() {
        queryDao.shutdown();
        logger.info("QueryDao resources released");

        try {
            if (jedisPool != null) {
                jedisPool.close();
                System.out.println("JedisPool closed successfully.");
            }
        } catch (Exception e) {
            System.err.println("Error while closing JedisPool: " + e.getMessage());
        } finally {
            super.destroy();
        }
    }

    /**
     * Validate the request url path according to the API spec
     * @param pathParts
     *  "/1/seasons/2019/day/1/skier/123" Eg. [, 1, seasons, 2019, day, 1, skier, 123]
     */
    private Boolean validateUrlPath(String[] pathParts) throws IOException {
        boolean isValidated = true;
        //         0        1       2           3      4     5       6      7
        // GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
        if (pathParts.length == 8 && "seasons".equals(pathParts[2]) && "day".equals(pathParts[4])
                && "skiers".equals(pathParts[6])) {
            int resortID = Integer.parseInt(pathParts[1]);
            int seasonID = Integer.parseInt(pathParts[3]);
            int dayID = Integer.parseInt(pathParts[5]);
            int currentYear = Year.now().getValue();
            if (!(resortID > 0 && seasonID >= 1900 && seasonID <= currentYear && dayID >= 1 && dayID <= 366)) {
                isValidated = false;
            }
        } else if (pathParts.length == 3 && "vertical".equals(pathParts[2])) {
            // GET /skiers/{skierID}/vertical
            int skierID = Integer.parseInt(pathParts[1]);
            if (!(skierID >= 1 && skierID <= 100000)) {
                isValidated = false;
            }
        } else {
            isValidated =  false;
        }

        return isValidated;
    }

    /**
     * Retrieves daily vertical from Redis cache or DynamoDB.
     */
    private Integer getDailyVertical(String[] pathParts) {
        String seasonID = pathParts[3];
        int dayID = Integer.parseInt(pathParts[5]);
        int skierID = Integer.parseInt(pathParts[7]);
        String cacheKey = String.format("daily:%s:%s:%s", seasonID, dayID, skierID);

        try (var jedis = jedisPool.getResource()) {
            // Check cache
            String cachedValue = jedis.get(cacheKey);
            if (cachedValue != null) {
                logger.info("Cache hit for key: " + cacheKey);
                return Integer.parseInt(cachedValue);
            }

            // Cache miss, fetch from DynamoDB
            logger.info("Cache miss for key: " + cacheKey);
            Integer totalVertical = queryDao.getDailyVertical(seasonID, dayID, skierID);

            // Store in cache
            jedis.setex(cacheKey, 3600, String.valueOf(totalVertical)); // Cache for 1 hour
            return totalVertical;
        }
    }

    /**
     * GET /skiers/{skierID}/verticaL
     * get the total vertical for the skier for specified seasons at the specified resort
     */
    private SkierVertical getResortVertical(Integer skierID, Integer resortID, String season) {
        String cacheKey = String.format("vertical:%d:%d:%s", skierID, resortID, season == null ? "all" : season);

        try (var jedis = jedisPool.getResource()) {
            // Check cache
            String cachedValue = jedis.get(cacheKey);
            if (cachedValue != null) {
                logger.info("Cache hit for key: " + cacheKey);
                return gson.fromJson(cachedValue, SkierVertical.class);
            }

            // Cache miss, fetch from DynamoDB
            logger.info("Cache miss for key: " + cacheKey);
            SkierVertical skierVertical = queryDao.getResortVertical(String.valueOf(skierID), String.valueOf(resortID), season);

            // Store in cache
            jedis.setex(cacheKey, 3600, gson.toJson(skierVertical)); // Cache for 1 hour
            return skierVertical;
        }
    }

    private void writeErrorResponse(HttpServletResponse resp, int statusCode, String message) throws IOException {
        resp.setStatus(statusCode);
        resp.getWriter().write(gson.toJson(new ResponseMsg().message(message)));
    }
}