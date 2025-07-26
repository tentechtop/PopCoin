package com.pop.popcoinsystem.data.storage.back;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于JVM的完整区块链代币合约示例
 * 包含完整的区块链架构、合约引擎和ERC20代币实现
 */
public class JVMTokenBlockchainExample {

    // ========================================
    // 区块链核心组件
    // ========================================

    /**
     * 区块类
     */
    static class Block {
        private String hash;
        private String previousHash;
        private long timeStamp;
        private int nonce;
        private List<Transaction> transactions;
        private List<ContractDeployment> contractDeployments;

        public Block(String previousHash) {
            this.previousHash = previousHash;
            this.timeStamp = new Date().getTime();
            this.transactions = new ArrayList<>();
            this.contractDeployments = new ArrayList<>();
            this.nonce = 0;
            this.hash = calculateHash();
        }

        public String calculateHash() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                StringBuilder input = new StringBuilder();
                input.append(previousHash)
                        .append(timeStamp)
                        .append(nonce)
                        .append(transactions.hashCode())
                        .append(contractDeployments.hashCode());

                byte[] hashBytes = digest.digest(input.toString().getBytes());
                StringBuilder hexString = new StringBuilder();
                for (byte b : hashBytes) {
                    hexString.append(String.format("%02x", b));
                }
                return hexString.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void mineBlock(int difficulty) {
            String target = new String(new char[difficulty]).replace('\0', '0');
            while (!hash.substring(0, difficulty).equals(target)) {
                nonce++;
                hash = calculateHash();
            }
            System.out.println("Block mined: " + hash);
        }

        public void addTransaction(Transaction transaction) {
            transactions.add(transaction);
        }

        public void addContractDeployment(ContractDeployment deployment) {
            contractDeployments.add(deployment);
        }

        // Getters
        public String getHash() { return hash; }
        public String getPreviousHash() { return previousHash; }
        public List<Transaction> getTransactions() { return transactions; }
        public List<ContractDeployment> getContractDeployments() { return contractDeployments; }
    }

    /**
     * 区块链类
     */
    static class Blockchain {
        private List<Block> chain;
        private int difficulty;
        private StateManager stateManager;

        public Blockchain(StateManager stateManager) {
            this.chain = new ArrayList<>();
            this.difficulty = 3;
            this.stateManager = stateManager;
            // 创建创世区块
            chain.add(createGenesisBlock());
        }

        private Block createGenesisBlock() {
            Block genesis = new Block("0");
            genesis.mineBlock(difficulty);
            return genesis;
        }

        public Block getLatestBlock() {
            return chain.get(chain.size() - 1);
        }

        public void addBlock(Block newBlock) {
            newBlock.mineBlock(difficulty);
            chain.add(newBlock);

            // 处理区块中的合约部署
            for (ContractDeployment deployment : newBlock.getContractDeployments()) {
                stateManager.deployContract(
                        deployment.getCreator(),
                        deployment.getContract(),
                        deployment.getInitParams()
                );
            }

            // 处理区块中的交易
            for (Transaction transaction : newBlock.getTransactions()) {
                processTransaction(transaction);
            }
        }

        private void processTransaction(Transaction transaction) {
            if (transaction.isContractTransaction()) {
                // 处理合约交易
                stateManager.getContractEngine().executeContract(
                        transaction.getContractId(),
                        transaction.getMethod(),
                        transaction.getArgs(),
                        transaction.getSender()
                );
            } else {
                // 处理普通转账
                Account sender = stateManager.getAccount(transaction.getSender());
                Account recipient = stateManager.getAccount(transaction.getRecipient());

                if (sender.getBalance() >= transaction.getAmount()) {
                    sender.setBalance(sender.getBalance() - transaction.getAmount());
                    recipient.setBalance(recipient.getBalance() + transaction.getAmount());
                }
            }
        }

        public boolean isChainValid() {
            for (int i = 1; i < chain.size(); i++) {
                Block currentBlock = chain.get(i);
                Block previousBlock = chain.get(i - 1);

                if (!currentBlock.getHash().equals(currentBlock.calculateHash())) {
                    System.out.println("Block " + i + " has invalid hash");
                    return false;
                }

                if (!currentBlock.getPreviousHash().equals(previousBlock.getHash())) {
                    System.out.println("Block " + i + " has invalid previous hash");
                    return false;
                }
            }
            return true;
        }

        public List<Block> getChain() {
            return chain;
        }
    }

    /**
     * 交易类
     */
    static class Transaction {
        private String id;
        private String sender;
        private String recipient;
        private long amount;
        private String contractId;
        private String method;
        private List<Object> args;
        private long timestamp;

        // 普通转账交易
        public Transaction(String sender, String recipient, long amount) {
            this.id = UUID.randomUUID().toString();
            this.sender = sender;
            this.recipient = recipient;
            this.amount = amount;
            this.contractId = null;
            this.method = null;
            this.args = null;
            this.timestamp = new Date().getTime();
        }

        // 合约交易
        public Transaction(String sender, String contractId, String method, List<Object> args) {
            this.id = UUID.randomUUID().toString();
            this.sender = sender;
            this.recipient = null;
            this.amount = 0;
            this.contractId = contractId;
            this.method = method;
            this.args = args;
            this.timestamp = new Date().getTime();
        }

        public boolean isContractTransaction() {
            return contractId != null;
        }

        // Getters
        public String getId() { return id; }
        public String getSender() { return sender; }
        public String getRecipient() { return recipient; }
        public long getAmount() { return amount; }
        public String getContractId() { return contractId; }
        public String getMethod() { return method; }
        public List<Object> getArgs() { return args; }
    }

    /**
     * 合约部署记录
     */
    static class ContractDeployment {
        private String id;
        private String creator;
        private Contract contract;
        private Map<String, Object> initParams;

        public ContractDeployment(String creator, Contract contract, Map<String, Object> initParams) {
            this.id = UUID.randomUUID().toString();
            this.creator = creator;
            this.contract = contract;
            this.initParams = initParams;
        }

        // Getters
        public String getId() { return id; }
        public String getCreator() { return creator; }
        public Contract getContract() { return contract; }
        public Map<String, Object> getInitParams() { return initParams; }
    }

    // ========================================
    // 状态管理和合约引擎
    // ========================================

    /**
     * 账户类
     */
    static class Account {
        private final String address;
        private long balance;
        private Map<String, ContractInstance> contracts;

        public Account(String address) {
            this.address = address;
            this.balance = 0;
            this.contracts = new HashMap<>();
        }

        // Getters and Setters
        public String getAddress() { return address; }
        public long getBalance() { return balance; }
        public void setBalance(long balance) { this.balance = balance; }
        public Map<String, ContractInstance> getContracts() { return contracts; }
        public void addContract(String contractId, ContractInstance contract) {
            contracts.put(contractId, contract);
        }
    }

    /**
     * 合约接口
     */
    interface Contract {
        String getId();
        String getOwner();
        void initialize(Map<String, Object> params);
        Object execute(String method, List<Object> args, Context context);
        Map<String, Object> getState();
    }

    /**
     * 合约实例
     */
    static class ContractInstance {
        private final String contractId;
        private final Contract contract;
        private Map<String, Object> state;
        private long creationTime;

        public ContractInstance(String contractId, Contract contract) {
            this.contractId = contractId;
            this.contract = contract;
            this.state = new HashMap<>();
            this.creationTime = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getContractId() { return contractId; }
        public Contract getContract() { return contract; }
        public Map<String, Object> getState() { return state; }
        public void setState(Map<String, Object> state) { this.state = state; }
        public long getCreationTime() { return creationTime; }
    }

    /**
     * 执行上下文
     */
    static class Context {
        private final String caller;
        private final long timestamp;
        private final long gasLimit;
        private long gasUsed;

        public Context(String caller) {
            this.caller = caller;
            this.timestamp = System.currentTimeMillis();
            this.gasLimit = 1000000;
            this.gasUsed = 0;
        }

        // Getters and Setters
        public String getCaller() { return caller; }
        public long getTimestamp() { return timestamp; }
        public long getGasLimit() { return gasLimit; }
        public long getGasUsed() { return gasUsed; }
        public void useGas(long amount) { this.gasUsed += amount; }
        public boolean hasEnoughGas(long amount) { return gasUsed + amount <= gasLimit; }
    }

    /**
     * 状态管理器
     */
    static class StateManager {
        private Map<String, Account> accounts;
        private ContractEngine contractEngine;

        public StateManager() {
            this.accounts = new HashMap<>();
            this.contractEngine = new ContractEngine(this);
        }

        public Account getAccount(String address) {
            return accounts.computeIfAbsent(address, Account::new);
        }

        public String deployContract(String creator, Contract contract, Map<String, Object> initParams) {
            Account creatorAccount = getAccount(creator);
            return contractEngine.deployContract(creator, contract, initParams);
        }

        public ContractInstance getContract(String contractId) {
            for (Account account : accounts.values()) {
                if (account.getContracts().containsKey(contractId)) {
                    return account.getContracts().get(contractId);
                }
            }
            return null;
        }

        public ContractEngine getContractEngine() {
            return contractEngine;
        }
    }

    /**
     * 合约引擎
     */
    static class ContractEngine {
        private StateManager stateManager;
        private long defaultGasLimit = 1000000;

        public ContractEngine(StateManager stateManager) {
            this.stateManager = stateManager;
        }

        public String deployContract(String ownerAddress, Contract contract, Map<String, Object> initParams) {
            Account owner = stateManager.getAccount(ownerAddress);
            String contractId = generateContractAddress(ownerAddress, contract.getClass().getName());

            // 初始化合约
            contract.initialize(initParams);

            // 创建合约实例
            ContractInstance instance = new ContractInstance(contractId, contract);
            owner.addContract(contractId, instance);

            return contractId;
        }

        public Object executeContract(String contractId, String method, List<Object> args, String caller) {
            ContractInstance instance = stateManager.getContract(contractId);
            if (instance == null) {
                return "Contract not found: " + contractId;
            }

            Context context = new Context(caller);
            return instance.getContract().execute(method, args, context);
        }

        private String generateContractAddress(String ownerAddress, String contractName) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String input = ownerAddress + contractName + System.currentTimeMillis();
                byte[] hash = digest.digest(input.getBytes());

                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    hexString.append(String.format("%02x", b));
                }
                return "0x" + hexString.toString().substring(0, 40);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ========================================
    // ERC20代币合约实现
    // ========================================

    static class ERC20TokenContract implements Contract {
        private String contractId;
        private String owner;
        private String name;
        private String symbol;
        private int decimals;
        private BigInteger totalSupply;
        private Map<String, BigInteger> balances;
        private Map<String, Map<String, BigInteger>> allowances;
        private boolean paused;

        // Gas 常量
        private static final long TRANSFER_GAS = 21000;
        private static final long APPROVE_GAS = 10000;
        private static final long TRANSFER_FROM_GAS = 25000;
        private static final long MINT_GAS = 30000;
        private static final long BURN_GAS = 25000;
        private static final long PAUSE_GAS = 15000;

        @Override
        public String getId() {
            return contractId;
        }

        @Override
        public String getOwner() {
            return owner;
        }

        @Override
        public void initialize(Map<String, Object> params) {
            this.contractId = UUID.randomUUID().toString();
            this.owner = (String) params.get("owner");
            this.name = (String) params.get("name");
            this.symbol = (String) params.get("symbol");
            this.decimals = (int) params.getOrDefault("decimals", 18);

            // 处理总供应量，考虑小数位
            BigInteger supply = (BigInteger) params.get("totalSupply");
            this.totalSupply = supply.multiply(BigInteger.TEN.pow(decimals));

            this.balances = new HashMap<>();
            this.allowances = new HashMap<>();
            this.paused = false;

            // 初始代币分配给合约创建者
            balances.put(owner, totalSupply);
        }

        @Override
        public Object execute(String method, List<Object> args, Context context) {
            // 检查合约是否暂停
            if (paused && !method.equals("unpause") && !method.equals("name") && !method.equals("symbol") && !method.equals("decimals") && !method.equals("totalSupply")) {
                return "Error: Contract is paused";
            }

            switch (method) {
                case "name":
                    return name;
                case "symbol":
                    return symbol;
                case "decimals":
                    return decimals;
                case "totalSupply":
                    return totalSupply;
                case "balanceOf":
                    return balanceOf((String) args.get(0));
                case "transfer":
                    return transfer((String) args.get(0), (BigInteger) args.get(1), context);
                case "approve":
                    return approve((String) args.get(0), (BigInteger) args.get(1), context);
                case "allowance":
                    return allowance((String) args.get(0), (String) args.get(1));
                case "transferFrom":
                    return transferFrom((String) args.get(0), (String) args.get(1), (BigInteger) args.get(2), context);
                case "mint":
                    return mint((String) args.get(0), (BigInteger) args.get(1), context);
                case "burn":
                    return burn((String) args.get(0), (BigInteger) args.get(1), context);
                case "pause":
                    return pause(context);
                case "unpause":
                    return unpause(context);
                default:
                    return "Unknown method: " + method;
            }
        }

        @Override
        public Map<String, Object> getState() {
            Map<String, Object> state = new HashMap<>();
            state.put("name", name);
            state.put("symbol", symbol);
            state.put("decimals", decimals);
            state.put("totalSupply", totalSupply);
            state.put("balances", balances);
            state.put("allowances", allowances);
            state.put("paused", paused);
            state.put("owner", owner);
            return state;
        }

        // ERC20核心方法实现
        public BigInteger balanceOf(String account) {
            return balances.getOrDefault(account, BigInteger.ZERO);
        }

        public Object transfer(String recipient, BigInteger amount, Context context) {
            String sender = context.getCaller();

            // 检查余额
            if (balanceOf(sender).compareTo(amount) < 0) {
                return "Error: Insufficient balance";
            }

            // 消耗Gas
            context.useGas(TRANSFER_GAS);

            // 更新余额
            balances.put(sender, balanceOf(sender).subtract(amount));
            balances.put(recipient, balanceOf(recipient).add(amount));

            return "Transfer successful: " + formatAmount(amount) + " " + symbol + " to " + recipient;
        }

        public Object approve(String spender, BigInteger amount, Context context) {
            String owner = context.getCaller();

            // 消耗Gas
            context.useGas(APPROVE_GAS);

            // 更新授权
            allowances.computeIfAbsent(owner, k -> new HashMap<>()).put(spender, amount);

            return "Approval successful: " + spender + " can spend " + formatAmount(amount) + " " + symbol;
        }

        public BigInteger allowance(String owner, String spender) {
            return allowances.getOrDefault(owner, new HashMap<>()).getOrDefault(spender, BigInteger.ZERO);
        }

        public Object transferFrom(String sender, String recipient, BigInteger amount, Context context) {
            String spender = context.getCaller();
            BigInteger allowed = allowance(sender, spender);

            // 检查授权和余额
            if (allowed.compareTo(amount) < 0) {
                return "Error: Transfer amount exceeds allowance";
            }

            if (balanceOf(sender).compareTo(amount) < 0) {
                return "Error: Insufficient balance";
            }

            // 消耗Gas
            context.useGas(TRANSFER_FROM_GAS);

            // 更新余额和授权
            balances.put(sender, balanceOf(sender).subtract(amount));
            balances.put(recipient, balanceOf(recipient).add(amount));
            allowances.get(sender).put(spender, allowed.subtract(amount));

            return "TransferFrom successful: " + formatAmount(amount) + " " + symbol + " from " + sender + " to " + recipient;
        }

        // 扩展功能：增发代币
        public Object mint(String account, BigInteger amount, Context context) {
            // 权限检查
            if (!context.getCaller().equals(owner)) {
                return "Error: Only owner can mint tokens";
            }

            // 消耗Gas
            context.useGas(MINT_GAS);

            // 增发代币
            totalSupply = totalSupply.add(amount);
            balances.put(account, balanceOf(account).add(amount));

            return "Mint successful: " + formatAmount(amount) + " " + symbol + " to " + account;
        }

        // 扩展功能：销毁代币
        public Object burn(String account, BigInteger amount, Context context) {
            // 权限检查
            if (!context.getCaller().equals(account)) {
                return "Error: Can only burn your own tokens";
            }

            // 检查余额
            if (balanceOf(account).compareTo(amount) < 0) {
                return "Error: Insufficient balance";
            }

            // 消耗Gas
            context.useGas(BURN_GAS);

            // 销毁代币
            totalSupply = totalSupply.subtract(amount);
            balances.put(account, balanceOf(account).subtract(amount));

            return "Burn successful: " + formatAmount(amount) + " " + symbol + " from " + account;
        }

        // 扩展功能：暂停合约
        public Object pause(Context context) {
            // 权限检查
            if (!context.getCaller().equals(owner)) {
                return "Error: Only owner can pause the contract";
            }

            // 消耗Gas
            context.useGas(PAUSE_GAS);

            // 暂停合约
            this.paused = true;

            return "Contract paused";
        }

        // 扩展功能：恢复合约
        public Object unpause(Context context) {
            // 权限检查
            if (!context.getCaller().equals(owner)) {
                return "Error: Only owner can unpause the contract";
            }

            // 消耗Gas
            context.useGas(PAUSE_GAS);

            // 恢复合约
            this.paused = false;

            return "Contract unpaused";
        }

        // 辅助方法：格式化代币数量（考虑小数位）
        private String formatAmount(BigInteger amount) {
            return amount.divide(BigInteger.TEN.pow(decimals)) + "." +
                    amount.mod(BigInteger.TEN.pow(decimals)).toString();
        }
    }

    // ========================================
    // 主程序示例
    // ========================================

    public static void main(String[] args) {
        // 初始化区块链和状态管理器
        StateManager stateManager = new StateManager();
        Blockchain blockchain = new Blockchain(stateManager);

        // 创建账户
        String alice = "0xAlice";
        String bob = "0xBob";
        String charlie = "0xCharlie";

        // 给账户充值原生代币（用于支付Gas）
        Account aliceAccount = stateManager.getAccount(alice);
        Account bobAccount = stateManager.getAccount(bob);
        Account charlieAccount = stateManager.getAccount(charlie);

        aliceAccount.setBalance(1000000);
        bobAccount.setBalance(1000000);
        charlieAccount.setBalance(1000000);

        // 准备部署代币合约
        System.out.println("===== 部署代币合约 =====");
        Map<String, Object> tokenParams = new HashMap<>();
        tokenParams.put("owner", alice);
        tokenParams.put("name", "Demo Token");
        tokenParams.put("symbol", "DEMO");
        tokenParams.put("decimals", 18);
        tokenParams.put("totalSupply", BigInteger.valueOf(1000000)); // 1,000,000 tokens

        ERC20TokenContract tokenContract = new ERC20TokenContract();

        // 创建并添加部署区块
        Block deployBlock = new Block(blockchain.getLatestBlock().getHash());
        deployBlock.addContractDeployment(new ContractDeployment(alice, tokenContract, tokenParams));
        blockchain.addBlock(deployBlock);

        // 获取合约地址（实际中应该从部署结果中获取）
        String tokenAddress = stateManager.getContractEngine().deployContract(alice, tokenContract, tokenParams);
        System.out.println("代币合约已部署，地址: " + tokenAddress);

        // 查看合约信息
        System.out.println("\n===== 合约信息 =====");
        System.out.println("名称: " + tokenContract.execute("name", null, new Context(alice)));
        System.out.println("符号: " + tokenContract.execute("symbol", null, new Context(alice)));
        System.out.println("小数位: " + tokenContract.execute("decimals", null, new Context(alice)));
        System.out.println("总供应量: " + tokenContract.execute("totalSupply", null, new Context(alice)));
        System.out.println("Alice余额: " + tokenContract.execute("balanceOf", List.of(alice), new Context(alice)));

        // 转账示例
        System.out.println("\n===== 转账示例 =====");
        // 创建并添加交易区块
        Block transferBlock = new Block(blockchain.getLatestBlock().getHash());
        transferBlock.addTransaction(new Transaction(
                alice,
                tokenAddress,
                "transfer",
                List.of(bob, BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18)))
        ));
        blockchain.addBlock(transferBlock);

        System.out.println("Alice向Bob转账100 DEMO");
        System.out.println("Bob余额: " + tokenContract.execute("balanceOf", List.of(bob), new Context(alice)));

        // 授权和代理转账示例
        System.out.println("\n===== 授权和代理转账示例 =====");
        // 创建并添加授权区块
        Block approveBlock = new Block(blockchain.getLatestBlock().getHash());
        approveBlock.addTransaction(new Transaction(
                bob,
                tokenAddress,
                "approve",
                List.of(charlie, BigInteger.valueOf(50).multiply(BigInteger.TEN.pow(18)))
        ));
        blockchain.addBlock(approveBlock);

        System.out.println("Bob授权Charlie可以花费50 DEMO");
        System.out.println("Charlie的授权额度: " + tokenContract.execute("allowance", List.of(bob, charlie), new Context(alice)));

        // 创建并添加代理转账区块
        Block transferFromBlock = new Block(blockchain.getLatestBlock().getHash());
        transferFromBlock.addTransaction(new Transaction(
                charlie,
                tokenAddress,
                "transferFrom",
                List.of(bob, charlie, BigInteger.valueOf(30).multiply(BigInteger.TEN.pow(18)))
        ));
        blockchain.addBlock(transferFromBlock);

        System.out.println("Charlie从Bob账户转账30 DEMO到自己账户");
        System.out.println("Bob余额: " + tokenContract.execute("balanceOf", List.of(bob), new Context(alice)));
        System.out.println("Charlie余额: " + tokenContract.execute("balanceOf", List.of(charlie), new Context(alice)));
        System.out.println("Charlie的剩余授权额度: " + tokenContract.execute("allowance", List.of(bob, charlie), new Context(alice)));

        // 增发代币示例
        System.out.println("\n===== 增发代币示例 =====");
        // 创建并添加增发区块
        Block mintBlock = new Block(blockchain.getLatestBlock().getHash());
        mintBlock.addTransaction(new Transaction(
                alice,
                tokenAddress,
                "mint",
                List.of(charlie, BigInteger.valueOf(200).multiply(BigInteger.TEN.pow(18)))
        ));
        blockchain.addBlock(mintBlock);

        System.out.println("Alice增发200 DEMO给Charlie");
        System.out.println("Charlie余额: " + tokenContract.execute("balanceOf", List.of(charlie), new Context(alice)));
        System.out.println("新的总供应量: " + tokenContract.execute("totalSupply", null, new Context(alice)));

        // 暂停合约示例
        System.out.println("\n===== 暂停合约示例 =====");
        // 创建并添加暂停区块
        Block pauseBlock = new Block(blockchain.getLatestBlock().getHash());
        pauseBlock.addTransaction(new Transaction(
                alice,
                tokenAddress,
                "pause",
                null
        ));
        blockchain.addBlock(pauseBlock);

        System.out.println("Alice暂停了合约");

        // 尝试在暂停后转账
        Block failedTransferBlock = new Block(blockchain.getLatestBlock().getHash());
        failedTransferBlock.addTransaction(new Transaction(
                charlie,
                tokenAddress,
                "transfer",
                List.of(bob, BigInteger.valueOf(10).multiply(BigInteger.TEN.pow(18)))
        ));
        blockchain.addBlock(failedTransferBlock);

        System.out.println("Charlie尝试转账10 DEMO给Bob: " +
                tokenContract.execute("balanceOf", List.of(bob), new Context(alice)));

        // 恢复合约
        Block unpauseBlock = new Block(blockchain.getLatestBlock().getHash());
        unpauseBlock.addTransaction(new Transaction(
                alice,
                tokenAddress,
                "unpause",
                null
        ));
        blockchain.addBlock(unpauseBlock);

        System.out.println("Alice恢复了合约");

        // 再次尝试转账
        Block successTransferBlock = new Block(blockchain.getLatestBlock().getHash());
        successTransferBlock.addTransaction(new Transaction(
                charlie,
                tokenAddress,
                "transfer",
                List.of(bob, BigInteger.valueOf(10).multiply(BigInteger.TEN.pow(18)))
        ));
        blockchain.addBlock(successTransferBlock);

        System.out.println("Charlie再次尝试转账10 DEMO给Bob");
        System.out.println("Bob最终余额: " + tokenContract.execute("balanceOf", List.of(bob), new Context(alice)));

        // 验证区块链完整性
        System.out.println("\n===== 区块链验证 =====");
        System.out.println("区块链有效性: " + blockchain.isChainValid());
        System.out.println("区块数量: " + blockchain.getChain().size());
    }
}