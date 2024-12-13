package org.sylvia;

import com.google.gson.Gson;
import io.swagger.client.model.ResortSkiers;
import io.swagger.client.model.ResponseMsg;
import org.sylvia.config.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Year;
import java.util.logging.Logger;

@WebServlet(name = "ResortServlet", value = "/resorts/*")
public class ResortServlet extends HttpServlet {

    private final Gson gson = new Gson();
    private final ResponseMsg responseMsg = new ResponseMsg();
    private final Logger logger = Logger.getLogger(ResortServlet.class.getName());
    private QueryDao queryDao;
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
     * GET /resorts/{resortID}/seasons/{seasonID}/day/{dayID}/skiers
     *          0       1       2           3       4   5       6
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo();
        if (path == null || path.isEmpty()) {
            writeErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "Missing or invalid path");
            return;
        }

        String[] pathParts = path.split("/");
        // logger.info("debug: " + pathParts[0] + ", " + pathParts[1]);
        if (validateUrlPath(pathParts)) {
            int resortID = Integer.parseInt(pathParts[1]);
            int seasonID = Integer.parseInt(pathParts[3]);
            int dayID = Integer.parseInt(pathParts[5]);

            try {
                String jsonResponse = getResortSkiersFromCacheOrDB(resortID, seasonID, dayID);
                if (jsonResponse == null) {
                    writeErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "Data not found");
                    return;
                }

                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(jsonResponse);
            } catch (Exception e) {
                logger.severe("Error processing request: " + e.getMessage());
                e.printStackTrace();
                writeErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
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

    private Boolean validateUrlPath(String[] pathParts) {
        if (!(pathParts.length == 7 && "seasons".equals(pathParts[2]) && "day".equals(pathParts[4])
                && "skiers".equals(pathParts[6]))) {
            return false;
        }

        int resortID = Integer.parseInt(pathParts[1]);
        int seasonID = Integer.parseInt(pathParts[3]);
        int dayID = Integer.parseInt(pathParts[5]);
        int currentYear = Year.now().getValue();
        if (!(resortID > 0 && seasonID >= 1900 && seasonID <= currentYear && dayID >= 1 && dayID <= 366)) {
            return false;
        }

        return true;
    }

    private String getResortSkiersFromCacheOrDB(int resortID, int seasonID, int dayID) {
        String cacheKey = String.format("resort:%d:season:%d:day:%d:skiers", resortID, seasonID, dayID);

        try (Jedis jedis = jedisPool.getResource()) {
            // Attempt to fetch from Redis
            String cachedValue = jedis.get(cacheKey);
            if (cachedValue != null) {
                logger.info("Cache hit for key: " + cacheKey);
                return cachedValue;
            }

            // Cache miss, query the database
            logger.info("Cache miss for key: " + cacheKey);
            int uniqueSkiers = queryDao.getUniqueSkierNumbers(resortID, seasonID, dayID);

            // Prepare response and cache it
            String jsonResponse = gson.toJson(new ResortSkiers()
                    .time(String.valueOf(resortID))
                    .numSkiers(uniqueSkiers));

            jedis.setex(cacheKey, 3600, jsonResponse); // Cache the result for 1 hour
            return jsonResponse;
        }
    }

    private void writeErrorResponse(HttpServletResponse resp, int statusCode, String message) throws IOException {
        resp.setStatus(statusCode);
        resp.getWriter().write(gson.toJson(responseMsg.message(message)));
    }
}
