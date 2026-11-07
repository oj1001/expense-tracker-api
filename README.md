# Morrow expense API

Spring Boot 3.5 / Java 21 REST API for the Morrow mobile app. The API supports creating, listing, and deleting expenses, plus monthly spending summaries. It uses H2 for a quick local run and PostgreSQL in Docker.

## Run locally

With Java 21 and Maven installed:

```powershell
mvn spring-boot:run
```

The in-memory H2 database is used by default. To run the API with PostgreSQL instead:

```powershell
docker compose up --build
```

The API listens on `http://localhost:8080`. Useful endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/expenses` | List expenses, newest first |
| `POST` | `/api/expenses` | Create an expense |
| `DELETE` | `/api/expenses/{id}` | Delete an expense |
| `GET` | `/api/summary?month=2026-11` | Get a monthly total and category breakdown |
| `POST` | `/api/expenses/import` | Import a PhonePe CSV, XLS, or XLSX statement (`file` multipart field) |
| `GET` | `/actuator/health` | Health check |

PhonePe's merchant APIs do not provide a supported way for this app to fetch a user's personal transaction history directly, so export a statement from PhonePe and upload the CSV or Excel file instead. The importer looks for date, details/description, and amount columns, skips credits and unsuccessful transactions, categorizes common merchants, and ignores transaction IDs already imported. Transactions without an ID cannot be deduplicated across separate uploads.

Example request:

```json
{
  "description": "Coffee",
  "amount": 180.00,
  "category": "FOOD",
  "spentAt": "2026-11-06T10:30:00Z"
}
```

Categories are `FOOD`, `TRANSPORT`, `SHOPPING`, `BILLS`, `HEALTH`, and `OTHER`. Monetary values are represented as decimal amounts in INR.

## Kubernetes

Build the image and load it into your cluster, then create the database credentials and apply the manifests:

```powershell
docker build -t expense-tracker-api:latest .
kubectl create secret generic expense-db-secret --from-literal=username=expense --from-literal=password=<choose-a-password>
kubectl apply -k k8s
kubectl port-forward service/expense-api 8080:8080
```

The manifests use a persistent volume claim for PostgreSQL and keep the API service internal to the cluster. Configure your cluster's storage class before applying if it has no default. Do not expose this starter API publicly as-is: it does not yet include user authentication or authorization. Replace the example CORS origins and configure managed database credentials before any shared deployment.

## Test

```powershell
mvn test
```
