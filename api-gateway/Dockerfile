# Step 1: Use a lightweight Java 21 JRE runtime based on Alpine Linux
FROM eclipse-temurin:21-jre-alpine

# Step 2: Set the container's default timezone to UTC (matches PostgreSQL UTC standard)
ENV TZ=UTC

# Step 3: Create and switch to the working directory inside the container
WORKDIR /app

# Step 4: Copy the pre-built Spring Boot executable JAR from the host target/ folder
COPY target/*.jar app.jar

# Step 5: Document the port on which account-service listens (informational)
EXPOSE 8080

# Step 6: Start the Spring Boot application with JVM timezone set to UTC
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
