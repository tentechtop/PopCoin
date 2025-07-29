package com.pop.popcoinsystem.application.service;


import com.pop.popcoinsystem.application.service.vo.BuildWalletUTXODTO;
import com.pop.popcoinsystem.application.service.vo.TransferVO;
import com.pop.popcoinsystem.application.service.vo.WalletBalanceVO;
import com.pop.popcoinsystem.data.enums.SIGHASHTYPE;
import com.pop.popcoinsystem.data.script.ScriptPubKey;
import com.pop.popcoinsystem.data.script.ScriptSig;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.data.vo.result.RocksDbPageResult;
import com.pop.popcoinsystem.exception.InsufficientFundsException;
import com.pop.popcoinsystem.exception.UnsupportedAddressException;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.service.UTXOService;
import com.pop.popcoinsystem.util.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.pop.popcoinsystem.data.storage.POPStorage.getUTXOKey;

@Slf4j
@Service
public class WalletService {
    @Resource
    private UTXOService utxoService;

    @Resource
    private BlockChainService blockChainService;

    private static WalletStorage walletStorage;
    public static Wallet walleta;
    public static Wallet walletb;


    static {
        log.info("钱包初始化 WalletService init");
        //获取公钥和私钥  初始化两个钱包 A B
        //先从数据库中获取公钥和私钥
        walletStorage = WalletStorage.getInstance();
        walleta = walletStorage.getWallet("walleta");
        log.info("钱包A"+walleta);
        if (walleta == null){
            KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairA.getPrivate();
            PublicKey publicKey = keyPairA.getPublic();
            walleta = new Wallet();
            walleta.setName("walleta");
            walleta.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walleta.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            walletStorage.addWallet(walleta);
        }
        walletb = walletStorage.getWallet("walletb");
        log.info("钱包B"+walletb);
        if (walletb == null){
            KeyPair keyPairB = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairB.getPrivate();
            PublicKey publicKey = keyPairB.getPublic();
            walletb = new Wallet();
            walletb.setName("walletb");
            walletb.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walletb.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            walletStorage.addWallet(walletb);
        }
    }


    public Result<Wallet> createWallet(WalletVO walletVO) {
        Wallet wallet = null;
        if (walletVO.getWalletType()==WalletType.COMMON.getValue()){
            wallet = createNormalWallet(walletVO);
        }else if (walletVO.getWalletType()==WalletType.PASSWORD.getValue()){
            wallet = createPasswordWallet(walletVO);
        }else if (walletVO.getWalletType()==WalletType.MNEMONIC.getValue()){
            wallet = createMnemonicWallet(walletVO);
        }else if (walletVO.getWalletType()==WalletType.PASSWORD_MNEMONIC.getValue()){
            wallet = createPasswordMnemonicWallet(walletVO);
        }else{
            return Result.error("钱包类型错误");
        }
        return Result.OK(wallet);
    }


    private Wallet createNormalWallet(WalletVO walletVO) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        Wallet wallet = new Wallet();
        wallet.setName(walletVO.getName());
        wallet.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
        wallet.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
        wallet.setWalletType(WalletType.COMMON.getValue());
        return wallet;
    }

    private Wallet createPasswordWallet(WalletVO walletVO) {
        // 验证密码
        if (walletVO.getPassword() == null || walletVO.getPassword().isEmpty()) {
            throw new IllegalArgumentException("创建密码钱包需要提供密码");
        }

        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // 使用密码加密私钥
        String encryptedPrivateKey = CryptoUtil.encryptWithPassword(
                privateKey.getEncoded(),
                walletVO.getPassword()
        );
        Wallet wallet = new Wallet();
        wallet.setName(walletVO.getName());
        wallet.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
        wallet.setPrivateKeyHex(encryptedPrivateKey);
        wallet.setWalletType(WalletType.PASSWORD.getValue());
        wallet.setPasswordHash(CryptoUtil.hashPassword(walletVO.getPassword())); // 存储密码哈希用于验证
        return wallet;
    }




    /**
     * 助记词钱包（通常指基于助记词机制的区块链钱包）的核心作用是通过助记词这一易记形式，实现对数字资产的安全管理、备份与恢复，其核心价值体现在以下几个方面：
     * 1. 替代复杂私钥，简化资产控制权
     * 区块链中，数字资产（如比特币、以太坊等）的所有权由 “私钥” 决定，私钥是一串随机的长字符串（如 64 位十六进制数），难以记忆和手动记录。
     * 助记词通过特定算法（如 BIP-39 标准）将私钥转化为 12/18/24 个常见单词的序列，大幅降低了用户记录和保存的难度，同时助记词与私钥完全等价—— 拥有助记词即可推导出私钥，进而完全掌控对应钱包中的所有资产。
     * 2. 唯一且不可替代的备份工具
     * 助记词是钱包的 “终极备份”。当钱包软件损坏、设备丢失（如手机损坏、被盗）或需要更换设备时，用户只需在新设备的钱包中输入正确的助记词，即可重新生成对应的私钥，完整恢复钱包中的所有资产（包括地址、余额、交易记录等）。
     * 这意味着：只要助记词未丢失，无论钱包软件或设备出现任何问题，资产都能通过助记词找回。
     * 3. 跨平台 / 客户端的统一恢复入口
     * 基于同一助记词标准（如 BIP-39）的钱包，支持在不同品牌、不同类型的客户端（如手机 App、电脑软件、硬件钱包等）中恢复。
     * 例如，用户在 A 品牌钱包中生成的助记词，可在 B 品牌钱包中输入并恢复资产，无需依赖特定平台，提升了资产管理的灵活性。
     * 4. 体现区块链的去中心化特性
     * 助记词由用户自行生成和保管，无需依赖银行、交易所等第三方机构。用户完全掌控助记词，即完全掌控资产，避免了第三方平台倒闭、被黑客攻击或监管冻结带来的资产风险，体现了区块链 “去中心化” 的核心理念。
     * 关键提醒
     * 助记词的安全性直接决定资产安全：
     *
     * 一旦泄露，任何人都可通过助记词转移资产；
     * 一旦丢失且无备份，资产将永久无法找回。
     *
     * 因此，助记词需离线手写备份（避免拍照、截图），存放在安全地点，且不可泄露给任何人。
     *
     * 简言之，助记词钱包的核心作用是：用易记的单词序列替代复杂私钥，让用户安全、便捷地掌控数字资产，并实现跨场景的备份与恢复。
     * @param walletVO
     * @return
     */
    private Wallet createMnemonicWallet(WalletVO walletVO) {
        String password = walletVO.getPassword();

        byte[] initialEntropy = new byte[16];
        SecureRandomUtils.secureRandom().nextBytes(initialEntropy);
        String mnemonic = MnemonicUtils.generateMnemonic(initialEntropy);
        byte[] seed = MnemonicUtils.generateSeed(mnemonic, null);
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPairFromSeed(CryptoUtil.applySHA256(seed));
        PublicKey aPublic = keyPair.getPublic();
        PrivateKey aPrivate = keyPair.getPrivate();
        Wallet wallet = new Wallet();
        wallet.setName(walletVO.getName());

        return null;
    }


    //main
    //引入真正的随机性：密码学密钥必须由高质量的随机数生成，否则可能被攻击者预测。
    //作为种子生成的基础：后续的助记词（Mnemonic）、种子（Seed）、密钥对（KeyPair）都是基于这个初始熵衍生出来的。
    //16字节随机数) → 助记词 → 种子 → 私钥 → 公钥

    public static void main(String[] args) {
        Wallet wallet = walletStorage.getWallet("walleta");
        String publicKeyHex = wallet.getPublicKeyHex();
        byte[] bytes1 = CryptoUtil.hexToBytes(publicKeyHex);
        String p2PKHAddressByPK = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(bytes1);
        log.info("p2PKHAddressByPK: {}", p2PKHAddressByPK);

        String p2WPKHAddressByPK = CryptoUtil.ECDSASigner.createP2WPKHAddressByPK(bytes1);
        log.info("p2WPKHAddressByPK: {}", p2WPKHAddressByPK);



        //测试密码加密私钥
        String password = "123456";
        KeyPair keyPairP = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPairP.getPrivate();
        PublicKey publicKey = keyPairP.getPublic();

        // 使用密码加密私钥
        String encryptedPrivateKey = CryptoUtil.encryptWithPassword(
                privateKey.getEncoded(),
                password
        );
        log.info("privateKey: {}", CryptoUtil.bytesToHex(privateKey.getEncoded()));
        log.info("encryptedPrivateKey: {}", encryptedPrivateKey);
        //解密
        byte[] bytes = CryptoUtil.decryptWithPassword(
                encryptedPrivateKey,
                password
        );
        log.info("privateKey: {}", CryptoUtil.bytesToHex(bytes));




        // 16  24  32   只能是这些长度
        // 12  18  24
        //byte[] initialEntropy = new byte[16];
        // 生成256位熵（32字节），对应24个助记词
        byte[] initialEntropy = new byte[32];
        SecureRandomUtils.secureRandom().nextBytes(initialEntropy);
        String mnemonic = MnemonicUtils.generateMnemonic(initialEntropy);//12、18、24
        log.info("mnemonic: {}", mnemonic);

        log.info("mnemonic: {}", mnemonic.split(" ").length);


        String myMn = "upon way fade raven dose fetch master drive brief size skin verify utility upon seat wait behave enhance phrase tag vanish square release false";

        byte[] seed = MnemonicUtils.generateSeed(myMn, null); //可以有密码 也可以没有密码


        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPairFromSeed(CryptoUtil.applySHA256(seed));
        PublicKey aPublic = keyPair.getPublic();
        PrivateKey aPrivate = keyPair.getPrivate();
        log.info("publicKey: {}", aPublic);
        log.info("privateKey: {}", aPrivate);
        log.info("mnemonic: {}", mnemonic);
        log.info("seed: {}", seed);
        log.info("publicKey: {}", CryptoUtil.bytesToHex(keyPair.getPublic().getEncoded()));
        log.info("privateKey: {}", CryptoUtil.bytesToHex(keyPair.getPrivate().getEncoded()));


        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);

        byte[] signature = CryptoUtil.ECDSASigner.applySignature(keyPair.getPrivate(), txToSign);

        boolean b = CryptoUtil.ECDSASigner.verifySignature(keyPair.getPublic(), txToSign, signature);
        log.info("signature: {}",b);




        // 4. 生成根私钥和根链码（BIP-32）
        byte[] hmacResult = CryptoUtil.hmacSha512("Bitcoin seed".getBytes(), seed);
        byte[] masterPrivKeyBytes = Arrays.copyOfRange(hmacResult, 0, 32); // 根私钥（32字节）
        byte[] masterChainCode = Arrays.copyOfRange(hmacResult, 32, 64); // 根链码
        // 5. 派生默认路径子私钥（如以太坊：m/44'/60'/0'/0/0）
        List<Integer> defaultPath = Arrays.asList(
                44 | 0x80000000,  // 44'（强化派生）
                60 | 0x80000000,  // 60'（以太坊币种）
                0 | 0x80000000,   // 0'（账户0）
                0,                // 0（外部链）
                2                 // 0（第一个地址）
        );
        PrivateKey childPrivKey = CryptoUtil.deriveChildPrivateKey(masterPrivKeyBytes, masterChainCode, defaultPath);

        // 6. 生成公钥和地址
        PublicKey pubKey = CryptoUtil.derivePublicKey(childPrivKey);
        String pubKeyHex = CryptoUtil.bytesToHex(pubKey.getEncoded());
        String address = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(pubKey.getEncoded()); // 实现地址生成方法
        log.info("address: {}", address);


        byte[] signature2 = CryptoUtil.ECDSASigner.applySignature(childPrivKey, txToSign);

        boolean b2 = CryptoUtil.ECDSASigner.verifySignature(pubKey, txToSign, signature2);
        log.info("signature: {}",b2);


        ///m/44'/60'/0'/0/0：以太坊主账户的第一个接收地址
        //m/44'/60'/0'/0/1：以太坊主账户的第二个接收地址
        //m/44'/60'/0'/1/0：以太坊主账户的第一个 Change 地址（用于找零）



        //而是采用按需派生和 ** 间隙检测（Gap Limit）** 的策略。以下是详细解释：

    }




    private Wallet createPasswordMnemonicWallet(WalletVO walletVO) {

        return null;
    }
    // 用于管理钱包级别的锁
    private final ConcurrentHashMap<String, Object> walletLocks = new ConcurrentHashMap<>();

    /**
     * 创建交易  目前仅仅支持 P2PKH P2WPKH
     * @param transferVO  对方是什么地址，你就得按什么地址的规则发币，这是区块链交易的基础逻辑。
     * @return
     */
    synchronized public Result<String> createTransaction(TransferVO transferVO) {
        String walletName = transferVO.getWalletName();
        try {
            // 获取最新的UTXO状态，而不是依赖可能的缓存
            CopyOnWriteArraySet<String> walletUTXOs = walletStorage.getWalletUTXOs(walletName);

            // 使用更可靠的同步机制
            synchronized (getWalletLock(walletName)) {
                // 验证可用余额
                long availableBalance = calculateAvailableBalance(walletUTXOs);
                if (availableBalance < transferVO.getAmount()) {
                    return Result.error("余额不足");
                }
                String toAddress = transferVO.getToAddress();
                Transaction transaction = createTransactionByAddressType(transferVO, toAddress);
                blockChainService.verifyAndAddTradingPool(transaction);
                // 同步更新UTXO状态，确保一致性
                updateWalletUTXOs(walletName, walletUTXOs, transaction);
                return Result.OK(CryptoUtil.bytesToHex(transaction.getTxId()));
            }
        } catch (UnsupportedAddressException | InsufficientFundsException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("交易创建失败: " + e.getMessage());
        }
    }



    // 计算可用余额
    private long calculateAvailableBalance(Set<String> utxoKeys) {
        long total = 0;
        for (String utxoKey : utxoKeys) {
            UTXO utxo = blockChainService.getUTXO(utxoKey);
            if (utxo != null) {
                total += utxo.getValue();
            }
        }
        return total;
    }

    // 获取钱包锁对象，使用专门的锁管理器
    private Object getWalletLock(String walletName) {
        // 使用ConcurrentHashMap确保线程安全
        return walletLocks.computeIfAbsent(walletName, k -> new Object());
    }

    // 同步更新UTXO状态
    private void updateWalletUTXOs(String walletName,
                                   CopyOnWriteArraySet<String> walletUTXOs,
                                   Transaction transaction) {
        // 收集所有使用的UTXO
        Set<String> usedUTXOs = new HashSet<>();
        for (TXInput input : transaction.getInputs()) {
            String utxoKey = input.getTxId() + "-" + input.getVout();
            usedUTXOs.add(utxoKey);
        }

        // 从可用UTXO中移除已使用的
        walletUTXOs.removeAll(usedUTXOs);

        try {
            // 同步保存更新后的UTXO状态
            walletStorage.saveWalletUTXOs(walletName, walletUTXOs);
        } catch (Exception e) {
            // 记录错误并考虑回滚机制
            log.error("保存UTXO状态失败", e);
            throw new RuntimeException("保存交易状态失败", e);
        }
    }


    private Transaction createTransactionByAddressType(TransferVO transferVO, String toAddress)
            throws UnsupportedAddressException {
        TxSigType addressType = CryptoUtil.ECDSASigner.getAddressType(toAddress);
        switch (addressType) {
            case P2PKH:
            case P2SH:
                return createRegularTransaction(transferVO);
            case P2WPKH:
            case P2WSH:
                return createSegWitTransaction(transferVO);
            default:
                throw new UnsupportedAddressException("不支持的地址类型: " + addressType);
        }
    }


    private Transaction createRegularTransaction(TransferVO transferVO) {
        Wallet wallet = walletStorage.getWallet(transferVO.getWalletName());
        String publicKeyHex = wallet.getPublicKeyHex();
        String privateKeyHex = wallet.getPrivateKeyHex();
        byte[] publicKeyBytes = CryptoUtil.hexToBytes(publicKeyHex);
        byte[] privateKeyBytes = CryptoUtil.hexToBytes(privateKeyHex);
        PrivateKey privateKey = CryptoUtil.ECDSASigner.bytesToPrivateKey(privateKeyBytes);
        PublicKey publicKey = CryptoUtil.ECDSASigner.bytesToPublicKey(publicKeyBytes);


        byte[] fromAddressHash = CryptoUtil.ECDSASigner.createP2PKHByPK(publicKeyBytes);

        // 查询钱包可用的UTXO
        CopyOnWriteArraySet<String> walletUTXOs = walletStorage.getWalletUTXOs(transferVO.getWalletName());
        // 用于支付的UTXO key
        HashSet<String> usedUTXOs = new HashSet<>();

        String toAddress = transferVO.getToAddress();
        //地址转公钥哈希
        byte[] toAddressHash = CryptoUtil.ECDSASigner.getAddressHash(toAddress);


        long amountToPay = transferVO.getAmount();
        long totalInputAmount = 0L;

        // 创建交易对象
        Transaction transaction = new Transaction();
        ArrayList<TXInput> txInputs = new ArrayList<>();
        ArrayList<TXOutput> txOutputs = new ArrayList<>();

        // 迭代器用于分批处理UTXO
        Iterator<String> utxoIterator = walletUTXOs.iterator();
        boolean enoughFunds = false;

        while (utxoIterator.hasNext() && !enoughFunds) {
            // 分批获取50个UTXO
            List<String> batchUTXOs = new ArrayList<>();
            log.info("batchUTXOs: {}", batchUTXOs);
            int count = 0;
            while (utxoIterator.hasNext() && count < 50) {
                batchUTXOs.add(utxoIterator.next());
                count++;
            }
            log.info("batchUTXOs: {}", batchUTXOs);
            // 查询UTXO本体并累加金额
            for (String utxoKey : batchUTXOs) {
                UTXO utxo = blockChainService.getUTXO(utxoKey);
                if (utxo != null) {
                    usedUTXOs.add(utxoKey);
                    totalInputAmount += utxo.getValue();
                    // 创建交易输入
                    TXInput txInput = new TXInput();
                    txInput.setTxId(utxo.getTxId());
                    txInput.setVout(utxo.getVout());
                    byte[] txToSign = CryptoUtil.applySHA256(SerializeUtils.serialize(utxo));//对输入引用的UTXO签名 证明我有权力使用这个UTXO
                    log.info("签名输入 txToSign: {}", CryptoUtil.bytesToHex(txToSign));
                    //对输入签名
                    byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, txToSign);
                    ScriptSig scriptSig = new ScriptSig(signature, publicKey);
                    txInput.setScriptSig(scriptSig);
                    txInputs.add(txInput);
                }
                // 检查是否足够支付
                log.info("totalInputAmount: {}", totalInputAmount);
                log.info("enoughFunds: {}", enoughFunds);
                if (totalInputAmount >= amountToPay) {
                    enoughFunds = true;
                    break;
                }
            }
        }

        // 如果资金不足，处理异常情况
        if (!enoughFunds) {
            throw new InsufficientFundsException("可用余额不足");
        }
        //设置交易输入
        transaction.setInputs(txInputs);

        // 创建交易输出 (主输出)
        TXOutput mainOutput = new TXOutput();
        mainOutput.setValue(amountToPay);
        //创建锁定脚本
        ScriptPubKey pubKey = new ScriptPubKey(toAddressHash);
        mainOutput.setScriptPubKey(pubKey);
        txOutputs.add(mainOutput);
        transaction.setOutputs(txOutputs);

        // 预计算交易大小（不含找零输出）
        long estimatedSize  = transaction.calculateBaseSize();
        log.info("这笔交易的预计大小 size: {}", estimatedSize);
        long estimatedFee = estimatedSize * 1000;
        long initialChange = totalInputAmount - amountToPay - estimatedFee;
        if (initialChange > 0) {
            TXOutput changeOutput = new TXOutput();
            changeOutput.setValue(initialChange);
            //转给自己
            ScriptPubKey pubKey1 = new ScriptPubKey(fromAddressHash);//公钥哈希
            changeOutput.setScriptPubKey(pubKey1);
            txOutputs.add(changeOutput);
            log.info("转给自己: {}", txOutputs);

            // 更新交易输出并重新计算实际大小
            transaction.setOutputs(txOutputs);
            long actualSize = transaction.calculateBaseSize();
            log.info("这笔交易的实际大小 size: {}", actualSize);
            long actualFee = actualSize * 1000;
            long actualChange = totalInputAmount - amountToPay - actualFee;
            log.info("实际费用: {}", actualFee);
            log.info("实际找零: {}", actualChange);

            // 如果重新计算后找零有变化，进行调整
            if (actualChange != initialChange) {
                if (actualChange > 0) {
                    changeOutput.setValue(actualChange);
                } else {
                    // 若重新计算后不需要找零，移除找零输出
                    txOutputs.remove(changeOutput);
                    transaction.setOutputs(txOutputs);
                }
            }
        }
        // 最终确定交易属性
        transaction.setTime(System.currentTimeMillis());
        transaction.setSize(transaction.calculateBaseSize());
        transaction.calculateWeight();
        transaction.setVersion(1);
        transaction.setTime(System.currentTimeMillis()/1000);//秒级别
        transaction.setTxId(Transaction.calculateTxId(transaction));
        log.info("提交时交易ID:{}", CryptoUtil.bytesToHex(transaction.getTxId()));

        //对于已经使用的UTXO要删除
        // 查询钱包可用的UTXO  walletUTXOs  - 减去usedUTXOs

        log.info("刚刚使用的UTXO:{}", usedUTXOs);
        for (String usedUTXO : usedUTXOs){
            walletUTXOs.remove(usedUTXO);
        }
        new Thread(() -> {
            log.info("删除UTXO:{}", walletUTXOs);
            //重新保存
            try {
                walletStorage.saveWalletUTXOs(wallet.getName(), walletUTXOs);
            }catch (Exception e){
                log.error("保存钱包UTXO失败", e);
            }
        }).start();
        return transaction;
    }

    private Transaction createSegWitTransaction(TransferVO transferVO) {
        Wallet wallet = walletStorage.getWallet(transferVO.getWalletName());
        String publicKeyHex = wallet.getPublicKeyHex();
        String privateKeyHex = wallet.getPrivateKeyHex();
        byte[] publicKeyBytes = CryptoUtil.hexToBytes(publicKeyHex);
        byte[] privateKeyBytes = CryptoUtil.hexToBytes(privateKeyHex);

        PrivateKey privateKey = CryptoUtil.ECDSASigner.bytesToPrivateKey(privateKeyBytes);
        PublicKey publicKey = CryptoUtil.ECDSASigner.bytesToPublicKey(publicKeyBytes);



        byte[] fromAddressHash = CryptoUtil.ECDSASigner.createP2WPKHByPK(publicKeyBytes);

        String toAddress = transferVO.getToAddress();
        // 计算公钥哈希
        byte[] toAddressHash = CryptoUtil.ECDSASigner.getAddressHash(toAddress);

        // 查询钱包可用的UTXO
        CopyOnWriteArraySet<String> walletUTXOs = walletStorage.getWalletUTXOs(transferVO.getWalletName());
        // 用于支付的UTXO key
        HashSet<String> usedUTXOs = new HashSet<>();

        long amountToPay = transferVO.getAmount();
        long totalInputAmount = 0L;

        // 创建交易对象
        Transaction transaction = new Transaction();
        // 启用隔离见证标记
        transaction.setVersion(2);
        ArrayList<TXInput> txInputs = new ArrayList<>();
        ArrayList<TXOutput> txOutputs = new ArrayList<>();
        // 迭代器用于分批处理UTXO
        Iterator<String> utxoIterator = walletUTXOs.iterator();
        boolean enoughFunds = false;

        // 收集所有输入的UTXO信息，用于签名计算
        List<UTXO> inputUTXOs = new ArrayList<>();

        // 1. 选择输入并收集足够的金额
        while (utxoIterator.hasNext() && !enoughFunds) {
            List<String> batchUTXOs = new ArrayList<>();
            int count = 0;
            while (utxoIterator.hasNext() && count < 50) {
                batchUTXOs.add(utxoIterator.next());
                count++;
            }

            // 查询UTXO本体并累加金额
            for (String utxoKey : batchUTXOs) {
                UTXO utxo = blockChainService.getUTXO(utxoKey);
                if (utxo != null) {
                    usedUTXOs.add(utxoKey);
                    totalInputAmount += utxo.getValue();
                    inputUTXOs.add(utxo);
                    TXInput txInput = new TXInput();
                    txInput.setTxId(utxo.getTxId());
                    txInput.setVout(utxo.getVout());
                    txInput.setScriptSig(null);
                    txInputs.add(txInput);
                }
                // 检查是否足够支付
                if (totalInputAmount >= amountToPay) {
                    enoughFunds = true;
                    break;
                }
            }
        }

        // 如果资金不足，处理异常情况
        if (!enoughFunds) {
            throw new InsufficientFundsException("可用余额不足");
        }

        // 设置交易输入
        transaction.setInputs(txInputs);

        // 2. 创建输出
        // 主输出 - 支付给目标地址
        TXOutput mainOutput = new TXOutput();
        mainOutput.setValue(amountToPay);

        // 确定输出地址类型并设置相应的锁定脚本
        TxSigType toAddressType = CryptoUtil.ECDSASigner.getAddressType(toAddress);
        if (toAddressType == TxSigType.P2WPKH) {
            // P2WPKH 锁定脚本: 0 <20-byte key-hash>
            ScriptPubKey scriptPubKey = ScriptPubKey.createP2WPKH(toAddressHash);
            mainOutput.setScriptPubKey(scriptPubKey);
        } else if (toAddressType == TxSigType.P2WSH) {
            // P2WSH 锁定脚本: 0 <32-byte script-hash>
            byte[] scriptHash = CryptoUtil.ECDSASigner.getAddressHash(toAddress);
            ScriptPubKey scriptPubKey = ScriptPubKey.createP2WSH(scriptHash);
            mainOutput.setScriptPubKey(scriptPubKey);
        } else {
            throw new UnsupportedAddressException("接收地址类型不支持隔离见证: " + toAddressType);
        }

        txOutputs.add(mainOutput);

        // 计算找零金额
        long estimatedSize = transaction.calculateBaseSize() + 100L * txInputs.size(); // 预估大小，包含见证数据
        long estimatedFee = estimatedSize * 1000; // 每字节1000聪的手续费率
        long changeAmount = totalInputAmount - amountToPay - estimatedFee;

        // 3. 添加找零输出（如果有）
        if (changeAmount > 0) {
            TXOutput changeOutput = new TXOutput();
            changeOutput.setValue(changeAmount);
            // 创建P2WPKH格式的找零输出
            ScriptPubKey changeScriptPubKey = ScriptPubKey.createP2WPKH(fromAddressHash);
            changeOutput.setScriptPubKey(changeScriptPubKey);
            txOutputs.add(changeOutput);
        }

        // 设置交易输出
        transaction.setOutputs(txOutputs);

        // 4. 为每个输入创建隔离见证签名
        List<Witness> witnesses = new ArrayList<>();
        for (int i = 0; i < txInputs.size(); i++) {
            TXInput input = txInputs.get(i);
            UTXO utxo = inputUTXOs.get(i);

            // 创建签名哈希
            byte[] sigHash = createWitnessSignatureHash(
                    transaction,
                    i,
                    privateKey,
                    publicKeyBytes,
                    utxo.getValue(),
                    SIGHASHTYPE.ALL
            );

            // 生成ECDSA签名
            byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, sigHash);

            // 构建见证数据: [签名, 公钥]
            Witness witness = new Witness();
            witness.addItem(signature);
            witness.addItem(publicKeyBytes);
            witnesses.add(witness);
        }

        // 设置交易见证
        transaction.setWitnesses(witnesses);

        // 5. 最终确定交易属性
        transaction.setTime(System.currentTimeMillis() / 1000); // 秒级别
        transaction.setSize(transaction.calculateTotalSize()); // 计算包含见证的总大小
        transaction.calculateWeight();
        transaction.setTxId(Transaction.calculateTxId(transaction));

        log.info("创建隔离见证交易成功，TXID: {}", CryptoUtil.bytesToHex(transaction.getTxId()));

        // 6. 更新钱包UTXO集合（异步处理）
        for (String usedUTXO : usedUTXOs) {
            walletUTXOs.remove(usedUTXO);
        }

        new Thread(() -> {
            log.info("删除已使用的UTXO: {}", usedUTXOs);
            walletStorage.saveWalletUTXOs(wallet.getName(), walletUTXOs);
        }).start();

        return transaction;
    }


    /**
     * 当前交易结构：
     * 版本号（Version）
     * 锁定时间（Locktime）
     * 输入数量和输出数量
     * 被签名输入的特定信息：
     * 引用的前序输出哈希（UTXO 的 ID）
     * 引用的前序输出索引（UTXO 在原交易中的位置）
     * 序列号（Sequence Number，通常用于替代锁定时间）
     * 不包含解锁脚本（ScriptSig），因为这部分已移至见证数据
     * 所有输出：
     * 每个输出的金额（Value）
     * 每个输出的锁定脚本（ScriptPubKey，即收款地址的脚本）
     */

    /**
     * 创建隔离见证交易的签名哈希
     * @param tx 交易对象
     * @param inputIndex 输入索引  inputIndex指定当前签名对应的输入索引，确保签名仅针对当前输入的 UTXO 权限验证；
     * @param privateKey 私钥
     * @param amount 金额
     * @param sigHashType 签名哈希类型  sigHashType（如ALL、NONE、SINGLE）决定交易的哪些部分（输入、输出）会被纳入哈希计算（例如ALL表示包含所有输入和输出，防止任何部分被篡改）。
     * @return 签名哈希
     */
    private byte[] createWitnessSignatureHash(Transaction tx, int inputIndex, PrivateKey privateKey,byte[] publicKeyHash, long amount, SIGHASHTYPE sigHashType) {
        // 1. 复制交易对象，避免修改原交易
        Transaction txCopy = tx.copy();
        log.info("原交易，交易ID: {}", tx);
        log.info("复制后，交易ID: {}", txCopy);
        boolean equals = tx.equals(txCopy);
        log.info("交易对象是否相等: {}", equals);

        for (TXInput input : txCopy.getInputs()) {
            input.setScriptSig(null);
        }
        //全部置空
        log.info("对输入的签名部分全部置空");
        // 3. 获取当前处理的输入
        TXInput currentInput = txCopy.getInputs().get(inputIndex);
        log.info("当前处理的输入: {}", currentInput);
        UTXO currentUTXO = blockChainService.getUTXO(currentInput.getTxId(), currentInput.getVout()); // inputUTXOs 是当前输入引用的 UTXO 集合

        ScriptPubKey originalScriptPubKey  = currentUTXO.getScriptPubKey();
        currentInput.setScriptSig(ScriptSig.createP2WPKHTemp(originalScriptPubKey.getScriptBytes())); // 临时设置，仅用于签名
        // 4. 创建签名哈希
        return txCopy.calculateWitnessSignatureHash(inputIndex, amount, sigHashType);
    }









    /**
     * 构建钱包的UTXO集合
     *
     * 目前仅仅支持两种地址 P2PKH P2WPKH
     *
     */
    public Result<String> buildWalletUTXO(BuildWalletUTXODTO buildWalletUTXODTO) {
        //首先要获取全节点中所有的UTXO集合
        String walletName = buildWalletUTXODTO.getWalletName();
        Wallet wallet = walletStorage.getWallet(walletName);
        String publicKeyHex = wallet.getPublicKeyHex();
        //遍历节点中全部的UTXO 找到属于该钱包的UTXO
        byte[] publicKeyHash = CryptoUtil.hexToBytes(publicKeyHex);

        ScriptPubKey p2PKH = ScriptPubKey.createP2PKH(publicKeyHash);
        ScriptPubKey p2WPKH = ScriptPubKey.createP2WPKH(publicKeyHash);
        byte[] hash = p2PKH.getHash();
        byte[] hash1 = p2WPKH.getHash();
        //总额
        long total = 0;
        String cursor = null; // 字符串类型游标，初始为null
        boolean hasMore = true;

        while (hasMore) {
            try {
                CopyOnWriteArraySet<String> walletUTXOs = new CopyOnWriteArraySet<>();
                // 分页查询UTXO，每次请求500个
                RocksDbPageResult<UTXO> pageResult = blockChainService.queryUTXOPage(500, cursor);
                List<UTXO> pageUTXOs = pageResult.getData();

                // 筛选属于该钱包的UTXO
                for (UTXO utxo : pageUTXOs) {
                    ScriptPubKey scriptPubKey = utxo.getScriptPubKey();
                    byte[] scriptPubKeyHash = scriptPubKey.getHash();
                    if (Arrays.equals(scriptPubKeyHash, hash) || Arrays.equals(scriptPubKeyHash, hash1)) {
                        String utxoKey = getUTXOKey(utxo.getTxId(), utxo.getVout());
                        total+=utxo.getValue();
                        walletUTXOs.add(utxoKey);
                    }
                }
                // 更新游标和判断是否有下一页
                hasMore = !pageResult.isLastPage();
                log.info("是否还有下一页"+hasMore);
                cursor = pageResult.getLastKey();
                log.info("已处理 {} 个UTXO，找到 {} 个属于钱包 {}",
                        pageUTXOs.size(), walletUTXOs.size(), walletName);

                walletStorage.saveWalletUTXOs(walletName, walletUTXOs);
            } catch (Exception e) {
                log.error("获取UTXO分页失败", e);
                hasMore = false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        wallet.setBalance(total);
        walletStorage.addWallet(wallet);
        //log.info("钱包 {} 的UTXO集合构建完成，共找到 {} 个UTXO", walletName, walletUTXOs.size());
        return Result.ok("正在构建钱包的UTXO集合");
    }


    public Result getBalance(WalletBalanceVO walletBalanceVO) {
        Wallet wallet = walletStorage.getWallet(walletBalanceVO.getWalletName());
        return Result.ok(wallet);
    }
}
