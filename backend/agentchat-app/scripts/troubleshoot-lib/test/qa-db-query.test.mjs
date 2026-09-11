import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { runQaDb } from '../../qa_db_query_run.mjs';

// T3：QA 只读查库。docker exec mysql 全程 mock，验 SQL 组装/闸门/TSV 解析/错误分类/不泄密。

const PWD = 'S3CR3T_PW';
const cfg = {
  version: 1,
  databases: {
    'TETitcDRP-qa': {
      host: 'h.qa', port: 22344, user: 'TETitcDRP', database: 'TETitcDRP', passwordEnv: 'TETITCDRP_QA_PWD',
    },
  },
};
const creds = { TETITCDRP_QA_PWD: PWD, OTHER: 'x' };

function okSpawn(stdout) {
  const fn = (bin, args, opts) => {
    fn.lastCall = { bin, args, opts };
    return { status: 0, stdout, stderr: '' };
  };
  return fn;
}

const base = (extra = []) => ['--db', 'TETitcDRP-qa', '--table', 'hotel_order', ...extra];

describe('qa_db_query SQL 组装', () => {
  test('默认 SELECT * + LIMIT 20，mysql 通道参数完整', () => {
    const spawn = okSpawn('id\torder_no\n1\tSH001\n');
    const out = runQaDb(base(), cfg, creds, spawn);
    assert.equal(out.rowCount, 1);
    assert.deepEqual(out.columns, ['id', 'order_no']);
    assert.deepEqual(out.rows, [{ id: '1', order_no: 'SH001' }]);
    const sql = spawn.lastCall.args[spawn.lastCall.args.length - 1];
    assert.match(sql, /^SELECT \* FROM `hotel_order` LIMIT 20$/);
    assert.deepEqual(spawn.lastCall.args.slice(0, 4), ['exec', '-i', '-e', `MYSQL_PWD=${PWD}`]);
    assert.equal(spawn.lastCall.args[4], 'db-mysql-1');
    assert.ok(spawn.lastCall.args.includes('--batch'));
    assert.ok(spawn.lastCall.args.includes('--raw'));
    assert.ok(spawn.lastCall.args.includes('h.qa'));
    // 子进程环境被净化，密码只经 -e 传
    assert.equal(spawn.lastCall.opts.env.PATH, '/usr/bin:/bin');
  });

  test('fields 名单逐个反引号；where 透传；limit 硬上限 50', () => {
    const spawn = okSpawn('id\n1\n');
    runQaDb(base(['--fields', 'id, order_no', '--where', "order_no = 'SH001'", '--limit', '99']), cfg, creds, spawn);
    const sql = spawn.lastCall.args.at(-1);
    assert.match(sql, /^SELECT `id`, `order_no` FROM `hotel_order` WHERE order_no = 'SH001' LIMIT 50$/);
  });

  test('limit 默认 20、上限 50：传 10 生效', () => {
    const spawn = okSpawn('id\n');
    runQaDb(base(['--limit', '10']), cfg, creds, spawn);
    assert.match(spawn.lastCall.args.at(-1), /LIMIT 10$/);
  });

  test('db key 不在配置：USAGE，不连接', () => {
    let called = false;
    const out = runQaDb(base(['--db', 'TETitcDRP-product']), cfg, creds, () => { called = true; });
    assert.equal(out.errorClass, 'USAGE');
    assert.equal(called, false);
  });

  test('非法表名/字段名：USAGE', () => {
    assert.equal(runQaDb(base(['--table', 'a;DROP']), cfg, creds, () => {}).errorClass, 'USAGE');
    assert.equal(runQaDb(base(['--fields', 'sleep(1)']), cfg, creds, () => {}).errorClass, 'USAGE');
  });

  test('危险 where：USAGE，不连接（UNION/分号/注释/SLEEP）', () => {
    for (const w of ['1=1 UNION SELECT 1', '1; DROP TABLE t', "1-- x", '1 OR SLEEP(5)', '1 OR 1=1; TRUNCATE t']) {
      let called = false;
      const out = runQaDb(base(['--where', w]), cfg, creds, () => { called = true; return { status: 0, stdout: '' }; });
      assert.equal(out.errorClass, 'USAGE', w);
      assert.equal(called, false, w);
    }
  });
});

describe('qa_db_query 凭据与执行错误', () => {
  test('密码键缺失/空：DB_CREDENTIAL_MISSING，不连接', () => {
    let called = false;
    const out = runQaDb(base(), cfg, { TETITCDRP_QA_PWD: '' }, () => { called = true; });
    assert.equal(out.errorClass, 'DB_CREDENTIAL_MISSING');
    assert.match(out.hint, /dbs\.env/);
    assert.equal(called, false);
  });

  test('Access denied：DB_AUTH_ERROR', () => {
    const spawn = () => ({ status: 1, stdout: '', stderr: "ERROR 1045 (28000): Access denied for user 'TETitcDRP'@'x' (using password: YES)" });
    const out = runQaDb(base(), cfg, creds, spawn);
    assert.equal(out.errorClass, 'DB_AUTH_ERROR');
  });

  test('Unknown table：DB_BAD_TABLE', () => {
    const spawn = () => ({ status: 1, stdout: '', stderr: "ERROR 1146 (42S02): Table 'TETitcDRP.ghost' doesn't exist" });
    assert.equal(runQaDb(base(['--table', 'ghost']), cfg, creds, spawn).errorClass, 'DB_BAD_TABLE');
  });

  test('连不上：DB_UNAVAILABLE', () => {
    const spawn = () => ({ status: 1, stdout: '', stderr: 'ERROR 2003 (HY000): Can\'t connect to MySQL server' });
    assert.equal(runQaDb(base(), cfg, creds, spawn).errorClass, 'DB_UNAVAILABLE');
  });

  test('docker 不存在（ENOENT）：DB_UNAVAILABLE', () => {
    const spawn = () => ({ error: new Error('spawn docker ENOENT'), status: null, stdout: '', stderr: '' });
    assert.equal(runQaDb(base(), cfg, creds, spawn).errorClass, 'DB_UNAVAILABLE');
  });

  test('错误输出兜底脱敏：stderr 里混入密码也不回显', () => {
    const spawn = () => ({ status: 1, stdout: '', stderr: `weird failure pwd=${PWD} token ${PWD}` });
    const out = runQaDb(base(), cfg, creds, spawn);
    assert.ok(!JSON.stringify(out).includes(PWD));
  });
});

describe('qa_db_query 结果解析', () => {
  test('NO_ROW：rowCount=0 不是错误', () => {
    const out = runQaDb(base(), cfg, creds, okSpawn('id\torder_no\n'));
    assert.equal(out.error, undefined);
    assert.equal(out.rowCount, 0);
    assert.deepEqual(out.rows, []);
  });

  test('NULL→null、空串保留', () => {
    const out = runQaDb(base(), cfg, creds, okSpawn('a\tb\tc\nNULL\t\tx\n'));
    assert.deepEqual(out.rows, [{ a: null, b: '', c: 'x' }]);
  });

  test('单元格 >200 字符截断并标注', () => {
    const long = 'z'.repeat(220);
    const out = runQaDb(base(), cfg, creds, okSpawn(`note\n${long}\n`));
    assert.ok(out.rows[0].note.length < 220);
    assert.match(out.rows[0].note, /截断/);
    assert.equal(out.cellTruncated, 1);
  });
});
