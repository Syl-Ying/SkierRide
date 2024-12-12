package org.sylvia;

import com.google.gson.Gson;
import io.swagger.client.model.ResponseMsg;
import io.swagger.client.model.SkierVertical;

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

    @Override
    public void init() throws ServletException {
        super.init();
        queryDao = QueryDao.getInstance();
        queryDao.testDynamoDbConnection();
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
                int totalVertical = getDailyVerticalFromDDB(pathParts);
                // logger.info("debug: totoVertical" + String.valueOf(totalVertical));
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
                SkierVertical skierVertical = getResortVerticalFromDDB(skierID, resortID, season);
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
        logger.info("SkierServlet destroyed and QueryDao resources released");
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

    private Integer getDailyVerticalFromDDB(String[] pathParts) {
        String seasonID = pathParts[3];
        int dayID = Integer.parseInt(pathParts[5]);
        int skierID = Integer.parseInt(pathParts[7]);

        return queryDao.getDailyVertical(seasonID, dayID, skierID);
    }

    /**
     * GET /skiers/{skierID}/verticaL
     * get the total vertical for the skier for specified seasons at the specified resort
     */
        private SkierVertical getResortVerticalFromDDB(Integer skierID, Integer resortID, String season) {
        SkierVertical skierVertical = queryDao.getResortVertical(String.valueOf(skierID), String.valueOf(resortID), season);
        return skierVertical;
    }

    private void writeErrorResponse(HttpServletResponse resp, int statusCode, String message) throws IOException {
        resp.setStatus(statusCode);
        resp.getWriter().write(gson.toJson(new ResponseMsg().message(message)));
    }
}