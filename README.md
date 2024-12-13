- How to run client
  - Run the main() in SkierClient class.
- Where to change the Servlet URL
  - Change URL in SERVER_URL variable of HttpPostThread class.
- Where to find servlet war file to deploy on EC2
  - SkierRide/out/artifacts/Server_war/Server_war.war
- Future Improvements
  - Catch exception and writeResponse in doPost() of servlet, explicitly throw exceptions in helper methods
  - Fix POST servlet load balancer
  - Add destroy() in servlet to close channel poll and connection
  - Queue sharding + increase rate limiting qps -> increase client rqs
    - Partition the producer's workload. Tasks with skierID ≤ 5000 go to Queue 1. Tasks with skierID > 5000 go to Queue 2.
    - In case messages in the primary queue cannot be processed within a certain time, redirect them to a DLQ
  - Centralized Config server
  - 
- Tasks assigned

| Task                       | Deadline  | Assignee(s)          | Notes                                                                          |
|----------------------------|-----------|-----------------------|--------------------------------------------------------------------------------|
| Client Throttle (Done)     | 12/3      | Sylvia               | Reduce and maintain stable production rate                                     |
| Servlet GET APIs + DDB     | 12/4      | Sylvia  | 3 APIs + Redis <br>|
| JMeter: Write 3 Test Cases | 12/5      | Zhuofan  | Zhuofan to research how to proceed                                             |
| Pre-slides                 | 12/10 (Tue) | Zhuofan (create), Sylvia (revise) | Use draw.io for architecture diagrams                                          | |
