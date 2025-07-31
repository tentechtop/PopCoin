package com.pop.popcoinsystem.application.service.wallet;


import com.pop.popcoinsystem.application.service.wallet.vo.BuildWalletUTXODTO;
import com.pop.popcoinsystem.application.service.wallet.vo.TransferVO;
import com.pop.popcoinsystem.application.service.wallet.vo.WalletBalanceVO;
import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.*;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.data.vo.result.RocksDbPageResult;
import com.pop.popcoinsystem.exception.InsufficientFundsException;
import com.pop.popcoinsystem.exception.UnsupportedAddressException;
import com.pop.popcoinsystem.service.BlockChainService;
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
import static com.pop.popcoinsystem.service.BlockChainService.convertTransactionDTO;

@Slf4j
@Service
public class WalletService {

    @Resource
    private BlockChainService blockChainService;


    public static Wallet walleta;
    public static Wallet walletb;


    public static void main(String[] args) {
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




    private Wallet createPasswordMnemonicWallet(WalletVO walletVO) {

        return null;
    }
    // 用于管理钱包级别的锁
    private final ConcurrentHashMap<String, Object> walletLocks = new ConcurrentHashMap<>();

    //缓存已经使用的UTXO 测试
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> walletUsedUTXOs = new ConcurrentHashMap<>();



    /**
     * 创建交易  目前仅仅支持 P2PKH P2WPKH
     * @param transferVO  对方是什么地址，你就得按什么地址的规则发币，这是区块链交易的基础逻辑。
     * @return
     */
    public Result<TransactionDTO> createTransaction(TransferVO transferVO) {
        String walletName = transferVO.getWalletName();
        try {
            WalletStorage instance = WalletStorage.getInstance();
            Set<String> walletUTXOs = instance.getWalletUTXOs(walletName);
            Wallet wallet = instance.getWallet(walletName);
            long availableBalance = wallet.getBalance();
            if (availableBalance < transferVO.getAmount()) {
                return Result.error("余额不足");
            }
            String toAddress = transferVO.getToAddress();
            Transaction transaction = createTransactionByAddressType(transferVO, toAddress);
            blockChainService.verifyAndAddTradingPool(transaction);
            TransactionDTO transactionDTO = convertTransactionDTO(transaction);
            return Result.OK(transactionDTO);
        } catch (UnsupportedAddressException | InsufficientFundsException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("交易创建失败: " + e.getMessage());
        }
    }
    private Transaction createTransactionByAddressType(TransferVO transferVO, String toAddress)
            throws UnsupportedAddressException {
        AddressType addressType = CryptoUtil.ECDSASigner.getAddressType(toAddress);
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
        WalletStorage instance = WalletStorage.getInstance();
        Wallet wallet = instance.getWallet(transferVO.getWalletName());
        String publicKeyHex = wallet.getPublicKeyHex();
        String privateKeyHex = wallet.getPrivateKeyHex();
        byte[] publicKeyBytes = CryptoUtil.hexToBytes(publicKeyHex);
        byte[] privateKeyBytes = CryptoUtil.hexToBytes(privateKeyHex);
        PrivateKey privateKey = CryptoUtil.ECDSASigner.bytesToPrivateKey(privateKeyBytes);
        PublicKey publicKey = CryptoUtil.ECDSASigner.bytesToPublicKey(publicKeyBytes);


        byte[] fromAddressHash = CryptoUtil.ECDSASigner.createP2PKHByPK(publicKeyBytes);

        // 查询钱包可用的UTXO
        Set<String> walletUTXOs = instance.getWalletUTXOs(transferVO.getWalletName());

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

                    //根据UTXO 决定是创建 解锁脚本 还是见证数据

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
        instance.removeWalletUTXOBatch(wallet.getName(), usedUTXOs);
        return transaction;
    }

    private Transaction createSegWitTransaction(TransferVO transferVO) {
        WalletStorage instance = WalletStorage.getInstance();
        Wallet wallet = instance.getWallet(transferVO.getWalletName());
        String publicKeyHex = wallet.getPublicKeyHex();
        String privateKeyHex = wallet.getPrivateKeyHex();
        byte[] publicKeyBytes = CryptoUtil.hexToBytes(publicKeyHex);
        byte[] privateKeyBytes = CryptoUtil.hexToBytes(privateKeyHex);

        PrivateKey privateKey = CryptoUtil.ECDSASigner.bytesToPrivateKey(privateKeyBytes);
        PublicKey publicKey = CryptoUtil.ECDSASigner.bytesToPublicKey(publicKeyBytes);

        byte[] fromAddressHash = CryptoUtil.ECDSASigner.createP2WPKHByPK(publicKeyBytes);//发送者P2WPKH公钥哈希

        String toAddress = transferVO.getToAddress();
        // 计算公钥哈希
        byte[] toAddressHash = CryptoUtil.ECDSASigner.getAddressHash(toAddress);//接收者公钥哈希

        // 查询钱包可用的UTXO
        Set<String> walletUTXOs = instance.getWalletUTXOs(transferVO.getWalletName());
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
        AddressType toAddressType = CryptoUtil.ECDSASigner.getAddressType(toAddress);
        if (toAddressType == AddressType.P2WPKH) {
            // P2WPKH 锁定脚本: 0 <20-byte key-hash>
            ScriptPubKey scriptPubKey = ScriptPubKey.createP2WPKH(toAddressHash);
            mainOutput.setScriptPubKey(scriptPubKey);
        } else if (toAddressType == AddressType.P2WSH) {
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

        /*交易的主体 和 输出部分全部构造完毕*/

        // 4. 为每个输入创建隔离见证签名
        List<Witness> witnesses = new ArrayList<>();
        for (int i = 0; i < txInputs.size(); i++) {
            TXInput input = txInputs.get(i);//当前输入
            UTXO utxo = inputUTXOs.get(i);
            // 创建签名哈希
            byte[] sigHash = blockChainService.createWitnessSignatureHash(
                    transaction,
                    i,
                    utxo.getValue(),
                    SigHashType.ALL
            );

            // 生成ECDSA签名
            byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, sigHash);
            byte[] sigHashType = SegWitUtils.createSigHashType(signature, SigHashType.ALL);
            // 构建见证数据: [签名, 公钥]
            Witness witness = new Witness();
            witness.addItem(sigHashType);
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
        log.info("创建时->交易ID: {}", CryptoUtil.bytesToHex(transaction.getTxId()));
        transaction.setWtxId(Transaction.calculateWtxId(transaction));
        log.info("创建时时->隔离见证ID:: {}", CryptoUtil.bytesToHex(transaction.getWtxId()));

        log.info("刚刚使用的UTXO{}",usedUTXOs);
        instance.removeWalletUTXOBatch(wallet.getName(), usedUTXOs);
        return transaction;
    }

//构造交易
//如果UTXO的脚本类型是P2PKH  则提供 签名:SigHashType 和 公钥     写入解锁脚本
//如果UTXO的脚本类型是P2SH   则提供 [签名:SigHashType], 赎回脚本 写入解锁脚本
//如果UTXO的脚本类型是P2WPKH 则提供 签名:SigHashType 和 公钥     写入见证数据  add(int index, E element) 确保顺序和输入的顺序一致
//如果UTXO的脚本类型是P2WSH  则提供 [签名:SigHashType], 赎回脚本 写入见证数据  add(int index, E element) 确保顺序和输入的顺序一致






    /**
     * 构建钱包的UTXO集合
     * 目前仅仅支持两种地址 P2PKH P2WPKH
     */
    public Result<String> buildWalletUTXO(BuildWalletUTXODTO buildWalletUTXODTO) {
        //首先要获取全节点中所有的UTXO集合
        WalletStorage instance = WalletStorage.getInstance();
        String walletName = buildWalletUTXODTO.getWalletName();
        Wallet wallet = instance.getWallet(walletName);
        String publicKeyHex = wallet.getPublicKeyHex();
        //遍历节点中全部的UTXO 找到属于该钱包的UTXO
        byte[] publicKey = CryptoUtil.hexToBytes(publicKeyHex);

        ScriptPubKey p2PKH = ScriptPubKey.createP2PKH(publicKey);
        log.info("构建钱包的UTXO->P2PKH: {}", p2PKH.toScripString());

        byte[] p2WPKHByPK = CryptoUtil.ECDSASigner.createP2WPKHByPK(publicKey);
        ScriptPubKey p2WPKH = ScriptPubKey.createP2WPKH(p2WPKHByPK);
        log.info("构建钱包的UTXO->P2WPKH: {}", p2WPKH.toScripString());

        byte[] hash = p2PKH.getHash();
        byte[] hash1 = p2WPKH.getHash();
        //总额
        long total = 0;
        String cursor = null; // 字符串类型游标，初始为null
        boolean hasMore = true;
        CopyOnWriteArraySet<String> walletUTXOs = new CopyOnWriteArraySet<>();
        while (hasMore) {
            try {
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

            } catch (Exception e) {
                log.error("获取UTXO分页失败", e);
                hasMore = false;
            }
        }
        wallet.setBalance(total);
        instance.addWallet(wallet);
        instance.saveWalletUTXOBatch(walletName, walletUTXOs);
        log.info("钱包 {} 的UTXO集合构建完成，共找到 {} 个UTXO", walletName, walletUTXOs.size());
        return Result.ok("正在构建钱包的UTXO集合");
    }


    public Result getBalance(WalletBalanceVO walletBalanceVO) {
        WalletStorage instance = WalletStorage.getInstance();
        Wallet wallet = instance.getWallet(walletBalanceVO.getWalletName());
        return Result.ok(wallet);
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















    /**
     * 创建交易  支持所有脚本类型UTXO（P2PKH/P2SH/P2WPKH/P2WSH）
     * @param transferVO 转账参数
     * @return 交易DTO
     */
    public Result<TransactionDTO> createTransaction1(TransferVO transferVO) {
        String walletName = transferVO.getWalletName();
        try {
            WalletStorage instance = WalletStorage.getInstance();
            Set<String> walletUTXOs = instance.getWalletUTXOs(walletName);
            Wallet wallet = instance.getWallet(walletName);
            long availableBalance = wallet.getBalance();
            if (availableBalance < transferVO.getAmount()) {
                return Result.error("余额不足");
            }
            String toAddress = transferVO.getToAddress();
            // 统一创建交易，不再区分地址类型
            Transaction transaction = createUniversalTransaction(transferVO, toAddress, walletUTXOs);
            blockChainService.verifyAndAddTradingPool(transaction);
            TransactionDTO transactionDTO = convertTransactionDTO(transaction);
            return Result.OK(transactionDTO);
        } catch (UnsupportedAddressException | InsufficientFundsException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("交易创建失败: " + e.getMessage());
        }
    }

    /**
     * 通用交易创建方法，根据UTXO脚本类型动态构造验证数据
     */
    private Transaction createUniversalTransaction(TransferVO transferVO, String toAddress, Set<String> walletUTXOs)
            throws UnsupportedAddressException, InsufficientFundsException {
        WalletStorage instance = WalletStorage.getInstance();
        Wallet wallet = instance.getWallet(transferVO.getWalletName());
        String publicKeyHex = wallet.getPublicKeyHex();
        String privateKeyHex = wallet.getPrivateKeyHex();
        byte[] publicKeyBytes = CryptoUtil.hexToBytes(publicKeyHex);
        byte[] privateKeyBytes = CryptoUtil.hexToBytes(privateKeyHex);
        PrivateKey privateKey = CryptoUtil.ECDSASigner.bytesToPrivateKey(privateKeyBytes);
        PublicKey publicKey = CryptoUtil.ECDSASigner.bytesToPublicKey(publicKeyBytes);

        // 接收地址处理
        byte[] toAddressHash = CryptoUtil.ECDSASigner.getAddressHash(toAddress);
        AddressType toAddressType = CryptoUtil.ECDSASigner.getAddressType(toAddress);

        // 交易基础信息
        Transaction transaction = new Transaction();
        List<TXInput> txInputs = new ArrayList<>();
        List<Witness> witnesses = new ArrayList<>(); // 隔离见证数据列表
        HashSet<String> usedUTXOs = new HashSet<>();
        long totalInputAmount = 0L;
        long amountToPay = transferVO.getAmount();
        boolean enoughFunds = false;

        // 迭代处理UTXO
        Iterator<String> utxoIterator = walletUTXOs.iterator();
        while (utxoIterator.hasNext() && !enoughFunds) {
            List<String> batchUTXOs = new ArrayList<>();
            int count = 0;
            while (utxoIterator.hasNext() && count < 50) {
                batchUTXOs.add(utxoIterator.next());
                count++;
            }

            for (String utxoKey : batchUTXOs) {
                UTXO utxo = blockChainService.getUTXO(utxoKey);
                if (utxo == null) continue;

                // 获取UTXO的脚本类型（核心：根据UTXO脚本类型处理）
                ScriptPubKey utxoScriptPubKey = utxo.getScriptPubKey();
                int scriptType = utxoScriptPubKey.getType();

                // 累加金额并标记使用的UTXO
                usedUTXOs.add(utxoKey);
                totalInputAmount += utxo.getValue();

                // 构建交易输入
                TXInput txInput = new TXInput();
                txInput.setTxId(utxo.getTxId());
                txInput.setVout(utxo.getVout());

                // 根据UTXO类型生成签名和验证数据
                byte[] sigHash = generateSigHash(transaction, txInputs.size(), utxo, scriptType);
                byte[] signature = CryptoUtil.ECDSASigner.applySignature(privateKey, sigHash);
                byte[] sigWithType = SegWitUtils.createSigHashType(signature, SigHashType.ALL);

                // 按脚本类型设置验证数据（解锁脚本或见证）
                if (isSegWitType(scriptType)) {
                    // 隔离见证类型：构建见证数据
                    Witness witness = createWitness(scriptType, sigWithType, publicKeyBytes, utxoScriptPubKey);
                    witnesses.add(witness);
                    txInput.setScriptSig(null); // 隔离见证输入的scriptSig为空
                } else {
                    // 普通类型：构建解锁脚本
                    ScriptSig scriptSig = createScriptSig(scriptType, sigWithType, publicKeyBytes, utxoScriptPubKey);
                    txInput.setScriptSig(scriptSig);
                }
                txInputs.add(txInput);
                // 检查金额是否足够
                if (totalInputAmount >= amountToPay) {
                    enoughFunds = true;
                    break;
                }
            }
        }

        // 资金不足校验
        if (!enoughFunds) {
            throw new InsufficientFundsException("可用余额不足");
        }

        // 设置交易输入和见证
        transaction.setInputs(txInputs);
        if (!witnesses.isEmpty()) {
            transaction.setWitnesses(witnesses);
            transaction.setVersion(2); // 隔离见证交易版本为2
        } else {
            transaction.setVersion(1); // 普通交易版本为1
        }

        // 构建交易输出
        List<TXOutput> txOutputs = new ArrayList<>();
        // 主输出（支付给接收地址）
        TXOutput mainOutput = new TXOutput();
        mainOutput.setValue(amountToPay);
        mainOutput.setScriptPubKey(createScriptPubKey(toAddressType, toAddressHash));
        txOutputs.add(mainOutput);

        // 计算手续费和找零
        calculateAndAddChange(transaction, txOutputs, totalInputAmount, amountToPay, publicKeyBytes, ScriptType.P2WSH.getValue());

        transaction.setOutputs(txOutputs);

        // 交易最终属性设置
        transaction.setTime(System.currentTimeMillis() / 1000);
        transaction.setSize(transaction.calculateTotalSize());
        transaction.calculateWeight();
        transaction.setTxId(Transaction.calculateTxId(transaction));
        if (!witnesses.isEmpty()) {
            transaction.setWtxId(Transaction.calculateWtxId(transaction));
            log.info("隔离见证交易ID: {}", CryptoUtil.bytesToHex(transaction.getWtxId()));
        }
        log.info("交易ID: {}", CryptoUtil.bytesToHex(transaction.getTxId()));

        // 移除使用过的UTXO  或者标记已经使用
        instance.removeWalletUTXOBatch(wallet.getName(), usedUTXOs);
        return transaction;
    }

// ------------------------------ 辅助方法 ------------------------------

    /**
     * 判断UTXO脚本类型是否为隔离见证
     */
    private boolean isSegWitType(int scriptType) {
        return scriptType == ScriptType.P2WPKH.getValue() || scriptType == ScriptType.P2WSH.getValue();
    }

    /**
     * 生成签名哈希
     */
    private byte[] generateSigHash(Transaction tx, int inputIndex, UTXO utxo, int scriptType) {
        if (isSegWitType(scriptType)) {
            return blockChainService.createWitnessSignatureHash(tx, inputIndex, utxo.getValue(), SigHashType.ALL);
        } else {
            return CryptoUtil.applySHA256(SerializeUtils.serialize(utxo));
        }
    }

    /**
     * 创建普通交易的解锁脚本
     */
    private ScriptSig createScriptSig(int scriptType, byte[] signature, byte[] pubKey, ScriptPubKey utxoScript) {
        switch (ScriptType.valueOf(scriptType)) {
            case P2PKH:
                return new ScriptSig(signature, pubKey);
            case P2SH:
                Script redeemScript = new Script();
                List<byte[]> signatures  = null;
                return ScriptSig.createP2SH(signatures, redeemScript);
            default:
                throw new UnsupportedAddressException("不支持的普通脚本类型: " + scriptType);
        }
    }

    /**
     * 创建隔离见证的见证数据
     */
    public Witness createWitness(int scriptTypeInt, byte[] signature, byte[] pubKey, ScriptPubKey utxoScript) {
        Witness witness = new Witness();
        // 1. 将int转换为ScriptType枚举（注意处理无效值的情况）
        ScriptType scriptType = ScriptType.valueOf(scriptTypeInt);
        if (scriptType == null) {
            throw new UnsupportedAddressException("不支持的隔离见证类型: " + scriptTypeInt);
        }
        // 2. 以枚举为switch表达式，直接使用枚举常量作为case标签
        switch (scriptType) {
            case P2SH:  // 直接使用枚举常量，无需getValue()
                witness.addItem(signature);
                witness.addItem(pubKey);
                break;
            case P2WSH:  // 对应原代码中的case 4（TYPE_P2WSH的value是4）
                witness.addItem(signature);
                byte[] redeemScript = null;  // 注意：这里传入null可能有问题，需确认业务逻辑
                witness.addItem(redeemScript);
                break;
            default:
                throw new UnsupportedAddressException("不支持的隔离见证类型: " + scriptTypeInt);
        }
        return witness;
    }

    /**
     * 创建输出锁定脚本
     */
    private ScriptPubKey createScriptPubKey(AddressType type, byte[] addressHash) {
        switch (type) {
            case P2PKH:
                return new ScriptPubKey(addressHash);
            case P2SH:
                return ScriptPubKey.createP2SH(addressHash);
            case P2WPKH:
                return ScriptPubKey.createP2WPKH(addressHash);
            case P2WSH:
                return ScriptPubKey.createP2WSH(addressHash);
            default:
                throw new UnsupportedAddressException("不支持的输出地址类型: " + type);
        }
    }

    /**
     * 计算并添加找零输出
     */
    private void calculateAndAddChange(Transaction tx, List<TXOutput> outputs, long totalInput, long amount, byte[] pubKeyBytes, int scriptType) {
        long size = tx.calculateTotalSize();
        long fee = size * 1000; // 每字节手续费
        long change = totalInput - amount - fee;
        if (change <= 0) return;

        // 找零地址类型与发送者UTXO类型一致
        AddressType changeType = isSegWitType(scriptType) ? AddressType.P2WPKH : AddressType.P2PKH;
        byte[] changeHash = isSegWitType(scriptType)
                ? CryptoUtil.ECDSASigner.createP2WPKHByPK(pubKeyBytes)
                : CryptoUtil.ECDSASigner.createP2PKHByPK(pubKeyBytes);

        TXOutput changeOutput = new TXOutput();
        changeOutput.setValue(change);
        changeOutput.setScriptPubKey(createScriptPubKey(changeType, changeHash));
        outputs.add(changeOutput);
    }














}
