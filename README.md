- The EC2 instances have been terminated.
- The initial code is based on https://github.com/Syl-Ying/Skier
- API Doc: https://app.swaggerhub.com/apis/cloud-perf/SkiDataAPI/2.0#/info

## Tasks Assigned

| Task                      | Deadline  | Assignee(s)               | Notes                                                                          |
|---------------------------|-----------|---------------------------|--------------------------------------------------------------------------------|
| Client Throttle           | 12/3      | Sylvia                    | Reduce and maintain stable production rate                                     |
| GET APIs + DDB + Redis    | 12/4      | Sylvia                    | 3 APIs <br>|
| JMeter: 3 Test Cases | 12/5      | Zhuofan                   | Zhuofan to research how to proceed                                             |
| Pre-slides                | 12/10 (Tue) | Zhuofan + Sylvia  | Use draw.io for architecture diagrams                                          | |

## Architecture
**![Architecture](/Pic/arch.png?raw=true "Architecture")**

The POST calling chain: Client → PostServlet → RabbitMQ → Consumer → DDB
The GET calling chain: JMeter -> QueryServlet -> Cache/DDB
- **Client**: Local machine.
- **Servlet server:** Deployed on **2** EC2 `t2.micro` instances using `Application Load Balancer`
- **RabbitMQ**: Deployed on 1 EC2 `t2.micro` instance.
- **Consumer:** Deployed on 1 EC2 `t3.large` instance using `Docker`. Because t2.micro only has 1 GB RAM, an out-of-memory (OOM) error occurred when running the Docker image on t2.micro, so a t3.large instance with 8 GB RAM was chosen.
- **DynamoDB**: AWS Database Service, on demand mode.
    - Used `VPC gateway endpoint` to receive request from consumer EC2 rather than through **open internet**, reducing network latency.

### POST Calling Chain:
**Client → PostServlet → RabbitMQ → Consumer → DynamoDB**

### GET Calling Chain:
**JMeter → QueryServlet → Cache/DynamoDB**

---

## System Components

### 1. **Producer (Client)**
- **Role**: Sends lift ride messages in JSON format to the system via HTTP POST requests.
-  To imitate a spiky traffic, the client first starts 32 threads, then another 168 threads, each sending 1k requests.
- Handles retries for failed message publishing with exponential backoff.

### 2. **PostServlet**
- **Deployment**: Two EC2 `t2.micro` instances behind an Application Load Balancer (ALB).
- **Responsibilities**:
    - Accepts HTTP POST requests from clients.
    - Validates incoming data (e.g., skier ID, resort ID, etc.).
    - Publishes validated messages to RabbitMQ using durable queues.
- **Features**:
    - Connection pooling for RabbitMQ channels.
    - Rate limiter

### 3. **QueryServlet**
- **Deployment**: Same as `PostServlet` 
- **Role**:
    - Handles GET requests for querying skier and resort data.
    - Queries DynamoDB or an in-memory cache for frequently accessed data.

### 4. **RabbitMQ**
- **Deployment**: Single `t2.micro` instance.
- **Role**:
    - Acts as a message broker, decoupling producers from consumers.
    - Ensures reliable message delivery using durable queues and acknowledgments.
- **Configuration**:
    - **Durable Queues**: Ensures messages are persisted in case of system failure.
    - **Acknowledgments**: Prevents message loss by ensuring consumers acknowledge successful processing.

### 5. **Consumer**
- **Deployment**: Docker container running on a `t3.large` EC2 instance (8 GB RAM) to avoid Out-Of-Memory (OOM) errors
- **Role**:
    - Reads messages from RabbitMQ.
    - Batches messages (up to 25 items) for efficient DynamoDB writes.
    - 
### 6. **Redis**
- **Deployment**: Single `t2.micro` instance.
- **Cache Layer**:
  - Used to reduce load on DynamoDB for repetitive queries.

### 7. **DynamoDB**
- **Configuration**:
    - On-demand mode to automatically scale with traffic.
    - **VPC Gateway Endpoint**: Ensures requests from the consumer instance are routed through the AWS private network, reducing latency and improving security.

---

## Database Design

1. **Analyze Requirement & Choose Database**
   - The requirement is to write to the database ideally as fast as you can consume messages from RabbitMQ. The system must handle high throughput while ensuring data consistency and scalability. RabbitMQ serves as the message broker, and DynamoDB is chosen as the primary database for its low latency, scalability, and ability to handle high write volumes.
   - **DynamoDB** is a NoSQL database, so I modeled the tables based on **access patterns** and **query requirements**.
2. **Analyze Swagger APIs / Queries**

    Most of the Swagger APIs are not required for Assignment 3 and Assignment 4. So they are not considered in data model design.
   - (Required for A3)`POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}`
       - Write a new lift ride for the skier
       - requestBody: `LiftRide` object {time, liftID}
       - return 201 | responseMsg
   - (Not specified in Swagger)`GET /skiers/seasons/{seasonID}/skiers/{skierID}`
     - **Query**: **"For skier N, how many days have they skied this season?"**
   - (Not specified in Swagger) `GET /skiers/seasons/{seasonID}/days/{dayID}/skiers/{skierID}`
     - **Query**: **"For skier N, show me the lifts they rode on each ski day"**
   - (Required for A4)`GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}`
          - Get total vertical for the day.
          - **Query**: **"For skier N, what are the vertical totals for each ski day?" (calculate vertical as liftID*10)**
   -  (Required for A4)`GET /resorts/{resortID}/seasons/{seasonID}/day/{dayID}/skiers`
       - Get number of unique skiers at resort/season/day
       - **Query**: **"How many unique skiers visited resort X on day N?"**
       - return `ResortSkiers` object {time: resortName, numSkiers} | responseMsg
   - (Required for A4)`GET/skiers/{skierID}/vertical`
       - Get the total vertical for the skier for specified seasons at the specified resort.
       - query parameters: `resort`/ `season`
       - return`SkierVertical` {resorts, [items {”seasonID”, totalVert}]}
   - `POST /resorts/{resortID}/seasons`
       - Create new season for a resort
       - return 201 | responseMsg
   - `GET /resorts/{resortID}/seasons`
       - Get a list of seasons for the specified resort
       - return `SeasonsList` | responseMsg
   - `GET /resorts`
       - Get resort list
       - return`ResortsList` arr  []
   - `GET /statistics`
       - return `APIStats` object. endpointStats [items{URL, operation, mean, max}]


4. **Adopted Data Model**
- Base Table: `skierTable`
    - **Table Schema**
    ```json 
    {
      "PK": "skierID",                // Partition Key: skier ID
      "SK": "seasonID#dayID#time",    // Sort Key: season ID, day ID and time combined
      "Attributes": {
        "LiftID": "5",               // Lift identifier
        "Time": "110",               // Time the lift was taken
        "vertical": 50,              //  vertical for the liftride (calculated as liftID * 10)
        "ResortID": "Resort123",     // Identifier for the resort
        "seasonID#dayID#skierID": "seasonID#dayID#skierID"        // GSI Sorting key 
      }
    }
    ```
- GSI: `resort-index`
    - **Table Schema**

        ```json
        {
          "PK": "resortID",                    // Partition Key: resort ID
          "SK": "seasonID#dayID#skierID"       // Sort Key: season ID, day ID and skierID combined
        }
        ```

  - Pros:
    - **Easy Write Operations:** Each lift ride is stored as a separate item, allowing the use of PutItem and BatchWriteItem. Simplifies the write logic, enabling high write throughput.
  - Cons:
    - **High Latency for Total Vertical Calculation**
      - To retrieve the totalVertical for a skier on a specific day or for a specific season, the system must query all individual lift ride items for that skier and day/season. This requires scanning or querying multiple items and aggregating the vertical values in the application layer.
      - DynamoDB doesn’t support server-side aggregation functions like SUM. All aggregation must be performed client-side, which is less efficient.
    - **Increased Read Costs**
      1. Reading multiple items consumes more Read Capacity Units (RCUs) in DynamoDB, leading to higher costs.

5. **Abandoned Data Model**
- Base Table: `skierTable`
    - **Table Schema**

        ```json
        {
          "PK": "skierID",                      // Partition Key: skier ID
          "SK": "seasonID#dayID",               // Sort Key: season ID and day ID combined
          "Attributes": {
            "liftList": [
              {
                "LiftID": "5",                  // Lift identifier
                "Time": "110"                   // Time the lift was taken
              },
              {
                "LiftID": "8",
                "Time": "334"
              }
            ],
            "TotalVertical": 130,              // Total vertical for the day (aggregated as liftID * 10)
            "ResortID": "Resort123",             // Identifier for the resort
            "seasonID#dayID#skierID": "seasonID#dayID#skierID"        // GSI Sorting key 
          }
        }
        ```

- GSI: `resort-index`
    - **Table Schema**

        ```json
        {
          "PK": "resortID",                      // Partition Key: resort ID
          "SK": "seasonID#dayID#skierID",        // Sort Key: season ID, day ID and skierID combined
        }
        ```

- Pros:
    - **Efficient Aggregated Queries:**
        - `TotalVertical` is pre-calculated and stored, enabling quick retrieval without the need for client-side aggregation.
        - Improves performance for GET endpoints that require total vertical data.
    - **Simplified Data Access:**
        - Storing LiftDetails as a list within a single item simplifies retrieval of all lift rides for a skier on a specific day.
        - Reduces the number of read operations needed.
- Cons:
    - **Complex Write Operations:**
        - Each write operation uses UpdateItem to append a new lift ride to the LiftDetails list. UpdateItem cannot be used with BatchWriteItem, limiting the ability to batch writes and potentially affecting write throughput.
        - UpdateItem operations may have higher latency compared to PutItem, especially when updating large lists. Could impact the overall system performance under high write load.
    - **Concurrency Control:**
        - Concurrent writes to the same item (same skierID and seasonID#dayID) may lead to conflicts. Requires careful handling of conditional updates or use of transactions to maintain data integrity. In our post API, since each skier only posts a new liftride in a new time, so the writes to the same item are in a certain order and barely concurrent.
    - **Item Size Limitations:**
        - DynamoDB items have a size limit of 400 KB. If a skier has many lift rides on a single day, the item may approach or exceed this limit, causing write failures.

-  `resortTable`
    - Table Schema

        ```json
        {
          "PK": "resortID",                      // Partition Key: resort ID
          "Attributes":  {
            "seasonList": ["2024", "2025"]       // season list
        }
        ```

    - Designed for `POST /resorts/{resortID}/seasons`, `GET /resorts/{resortID}/seasons` and `GET /resorts`  APIs.
    - Since it’s not required in assignment 3 and 4, it’s not implemented yet.
---
#  **Client Throughput** and **RMQ Console** 
### In-memory HashMap w/ a single POST Servlet EC2
![image.png](/Pic/client-map.png)
![image.png](/Pic/rmq-map.png)
### In-memory HashMap w/ 2 single POST Servlet EC2s and ALB
![image.png](/Pic/client-map-alb.png)
![image.png](/Pic/rmq-map-alb.png)
### After integrating DDB w/ Abandoned Data Model 
![image.png](/Pic/client-model2.png)
![image.png](/Pic/rmq-model2.png)
### After integrating DDB w/ Adopted Data Model
![image.png](/Pic/client-model1.png)
![image.png](/Pic/rmq-model1.png)
### After Integrating Servlet Rate Limiter + Client Exponential Retries
![image.png](/Pic/client-final.png)
![image.png](/Pic/rmq-final.png)

### JMeter - GET APIs
![image.png](/Pic/jmeter1.png)
![image.png](/Pic/jmeter2.png)
![image.png](/Pic/jmeter3.png)
---
# Future Improvements
- Catch exception and writeResponse in doPost() of POST servlet, explicitly throw exceptions in helper methods
- Fix POST servlet load balancer
- Queue sharding + increase rate limiting qps -> increase client rqs
  - Partition the producer's workload. Tasks with skierID ≤ 5000 go to Queue 1. Tasks with skierID > 5000 go to Queue 2.
  - In case messages in the primary queue cannot be processed within a certain time, redirect them to a DLQ
- Centralized Config server