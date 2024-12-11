package org.sylvia;

import com.google.gson.Gson;
import io.swagger.client.model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Year;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@WebServlet(name = "ResortServlet", value = "/resorts/*")
public class ResortServlet extends HttpServlet {

    private final Gson gson = new Gson();
    private final ResponseMsg responseMsg = new ResponseMsg();
    private final Logger logger = Logger.getLogger(ResortServlet.class.getName());
    private QueryDao queryDao;

    @Override
    public void init() throws ServletException {
        super.init();
        queryDao = QueryDao.getInstance();
        queryDao.testDynamoDbConnection();
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

            int uniqueSkiers = getUniqueSkiers(resortID, seasonID, dayID); // 404 data not found
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(uniqueSkiers));
        } else {
            writeErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid path format");
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

    private int getUniqueSkiers(int resortID, int seasonID, int dayID) {
        int uniqueSkier = queryDao.getUniqueSkierNumbers(resortID, seasonID, dayID);
        return uniqueSkier;
    }

    private void writeErrorResponse(HttpServletResponse resp, int statusCode, String message) throws IOException {
        resp.setStatus(statusCode);
        resp.getWriter().write(gson.toJson(responseMsg.message(message)));
    }

}
