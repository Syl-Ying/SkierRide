package org.sylvia;

import com.google.gson.Gson;
import io.swagger.client.model.ResponseMsg;

import java.io.*;
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

 /*   @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        String pathInfo = req.getPathInfo();

        // validate url path and request body
        if (!isUrlPathValid(pathInfo, resp)) {
            return;
        }


        // GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}

        // GET /skiers/{skierID}/vertical



        resp.setStatus(HttpServletResponse.SC_OK); // 200
        // TODO: process url params in `urlParts`
        resp.getWriter().write("200 It Works!");

    }
*/
    @Override
    public void destroy() {
    }
/*

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
*/

    private void writeErrorResponse(HttpServletResponse resp, int statusCode, String message) throws IOException {
        resp.setStatus(statusCode);
        resp.getWriter().write(gson.toJson(new ResponseMsg().message(message)));
    }
}