# Rinha de Backend 2025 - Payment Processor

Sistema inteligente de processamento de pagamentos para a **Rinha de Backend 2025** com health check adaptativo e sistema de auditoria completo.

## 🎯 Características Principais

### ⚡ **Performance Otimizada**
- **Processamento paralelo** com thread pool otimizado (cores * 2)
- **Batching inteligente** para melhor distribuição de carga
- **Health check proativo** que evita requisições desnecessárias
- **Timeout configurável** para evitar bloqueios

### 🧠 **Health Check Inteligente**
- **Payload real**: Usa requisições já processadas com sucesso para testar saúde do serviço
- **Monitoramento em background**: Verifica automaticamente a recuperação do processador default
- **Estratégia adaptativa**: 
  - Se DEFAULT healthy → tenta default primeiro
  - Se DEFAULT unhealthy → vai direto para fallback
- **Recuperação automática**: Detecta quando o serviço volta a funcionar

### 📊 **Sistema de Auditoria Completo**
- **Endpoints HTTP** para verificação de consistência
- **Armazenamento em memória** thread-safe
- **Contadores atômicos** para alta concorrência
- **Relatórios em tempo real**

## 🏗️ Arquitetura

### Componentes Principais

1. **SimplePaymentProcessor** - Motor de processamento principal
2. **AuditServer** - Servidor HTTP para auditoria e consistência
3. **Health Check System** - Monitoramento inteligente dos processadores

### Fluxo de Processamento

```
Pagamento → Health Check → DEFAULT/FALLBACK → Auditoria → Relatório
```

## 🚀 Como Executar

### 1. Compilar o Projeto
```bash
cd rinha-backend-2025
javac src\main\java\*.java -d target\classes
```

### 2. Executar o Audit Server
```bash
java -cp target\classes AuditServer
```

### 3. Executar o Payment Processor
```bash
java -cp target\classes SimplePaymentProcessor
```

## 📡 Endpoints da API

### Audit Server (localhost:8080)

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| POST | `/payments` | Recebe pagamentos processados |
| GET | `/payments-summary` | Resumo para auditoria |
| GET | `/payments-list` | Lista todos os pagamentos |
| POST | `/clear` | Limpa dados em memória |

### Exemplos de Resposta

**GET /payments-summary**
```json
{
  "totalRequests": 1000,
  "totalAmount": 19900.00,
  "timestamp": 1754028011143
}
```

## ⚙️ Configurações

### Variáveis Principais (SimplePaymentProcessor.java)

```java
private static final String DEFAULT_PROCESSOR = "http://localhost:8001/payments";
private static final String FALLBACK_PROCESSOR = "http://localhost:8001/payments";
private static final String AUDIT_ENDPOINT = "http://localhost:8080";
```

### Parâmetros de Performance

- **Thread Pool**: `Runtime.getRuntime().availableProcessors() * 2`
- **Batch Size**: `50 pagamentos por lote`
- **Timeout HTTP**: `3 segundos`
- **Health Check Interval**: `10 segundos`

## 🎛️ Estratégia de Processamento

### Default-First Strategy

1. **Verificação Prévia**: Consulta status de saúde do DEFAULT
2. **Tentativa Primária**: Se healthy, tenta DEFAULT primeiro
3. **Fallback Automático**: Se falha, marca como unhealthy e usa FALLBACK
4. **Recuperação Inteligente**: Health check em background detecta quando DEFAULT volta
5. **Auditoria**: Todos os pagamentos processados são enviados para auditoria

### Critérios de Sucesso

- **Status 2xx**: Processamento bem-sucedido
- **Status 4xx**: Erro do cliente (considerado sucesso - processador respondeu)
- **Status 5xx**: Falha do servidor (tentativa fallback)
- **Timeout/Exception**: Falha de conexão (tentativa fallback)

## 🔍 Monitoramento

### Logs em Tempo Real

```
🎯 Guardada requisição de teste para health check: abc-123
✓ Default OK: def-456 (status: 200)
📋 Auditoria OK: ghi-789
🔍 Testando health do default processor...
✅ Default processor está HEALTHY novamente!
```

### Métricas Disponíveis

- **Requests por processador** (default vs fallback)
- **Valores monetários processados**
- **Status de saúde dos processadores**
- **Tempo total de processamento**
- **Pagamentos em memória**

## 🧪 Testes

### Cenário de Teste Padrão
- **1000 pagamentos** de R$ 19,90 cada
- **Processamento paralelo** com 24 threads
- **Health check ativo** em background
- **Auditoria completa** de todos os pagamentos

### Resultados Esperados
```
===== RESUMO DE PROCESSAMENTO =====
Tempo total: 695ms
Default - Requests: 1000, Amount: $19900.0
Fallback - Requests: 0, Amount: $0.0
TOTAL - Requests: 1000, Amount: $19900.0
Pagamentos em memória: 1000
Default Processor Status: HEALTHY
===================================
```

## 🏆 Vantagens Competitivas

1. **Zero Dependências Externas**: Usa apenas Java nativo
2. **Health Check Inteligente**: Não depende de endpoints específicos
3. **Recuperação Automática**: Detecta quando serviços voltam a funcionar
4. **Performance Otimizada**: Thread pool e batching eficientes
5. **Auditoria Completa**: Sistema robusto de verificação de consistência
6. **Monitoramento em Tempo Real**: Logs detalhados e métricas precisas

## 📁 Estrutura do Projeto

```
rinha-backend-2025/
├── src/
│   └── main/
│       └── java/
│           ├── SimplePaymentProcessor.java
│           └── AuditServer.java
├── target/
│   └── classes/
└── README.md
```

## 🎯 Otimizações para a Rinha

- **Sem dependências externas** (JSON parsing manual)
- **Memory-only storage** para máxima velocidade
- **Atomic counters** para thread safety
- **Timeout agressivo** para evitar bloqueios
- **Health check preventivo** para reduzir falhas
- **Batch processing** para melhor throughput

---

**Pronto para a Rinha de Backend 2025! 🚀**
